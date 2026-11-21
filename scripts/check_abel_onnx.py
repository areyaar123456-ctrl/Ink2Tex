#!/usr/bin/env python3
"""Check the generated ONNX model against a direct NumPy reference.

Place the unmodified ABLM file beside the generated ONNX graphs. This test
exercises external weights, point-sequence lengths, full-prefix versus cached
decoding, and actual logits. The synthetic ink is a runtime fixture.
"""
import argparse
import json
import math
import struct
from pathlib import Path

import numpy as np
import onnxruntime as ort


def reference(directory):
    with (directory / "math_ink.ablm").open("rb") as file:
        assert file.read(4) == b"ABLM"
        version, size = struct.unpack("<II", file.read(8))
        assert version == 1
        header = json.loads(file.read(size))
        payload = file.read()
    weights = {
        t["name"]: np.frombuffer(payload, dtype="<f2", count=t["bytes"] // 2, offset=t["offset"])
        .reshape(t["shape"]).astype(np.float32) for t in header["tensors"]
    }

    def linear(x, name, bias=True):
        y = x @ weights[name + ".weight"].T
        return y + weights[name + ".bias"] if bias else y

    def gelu(x):
        return x * .5 * (1 + np.vectorize(math.erf, otypes=[np.float32])(x / np.float32(math.sqrt(2))))

    def normalize(x, name):
        centered = x - x.mean(axis=-1, keepdims=True)
        return centered / np.sqrt((centered ** 2).mean(axis=-1, keepdims=True) + 1e-5) *             weights[name + ".weight"] + weights[name + ".bias"]

    def positions(size):
        phase = np.arange(size)[:, None] / (10000 ** (np.arange(128) / 128))[None, :]
        return np.concatenate([np.sin(phase), np.cos(phase)], axis=1).astype(np.float32)

    def heads(x):
        return x.reshape(-1, 8, 32).transpose(1, 0, 2)

    def keys(x, name):
        return heads(linear(x, name + ".k_proj", False)), heads(linear(x, name + ".v_proj"))

    def attention(x, memory, name, causal=False):
        q = heads(linear(x, name + ".q_proj"))
        k, v = keys(memory, name)
        logits = (q @ k.transpose(0, 2, 1)) / np.float32(math.sqrt(32))
        if causal:
            logits[:, np.triu_indices(x.shape[0], 1)[0], np.triu_indices(x.shape[0], 1)[1]] = -np.inf
        logits = np.exp(logits - logits.max(axis=-1, keepdims=True))
        result = ((logits / logits.sum(axis=-1, keepdims=True)) @ v).transpose(1, 0, 2)
        return linear(result.reshape(-1, 256), name + ".out_proj")

    def feed(x, layer, norm):
        y = normalize(x, layer + "." + norm)
        return x + linear(gelu(linear(y, layer + ".ff.0")), layer + ".ff.3")

    def encode(points):
        x = points
        for layer in ("encoder.stem.0", "encoder.stem.2"):
            padded = np.pad(x, ((2, 2), (0, 0)))
            windows = np.lib.stride_tricks.sliding_window_view(padded, 5, axis=0)[::2]
            x = gelu(np.einsum("lck,dck->ld", windows, weights[layer + ".weight"]) + weights[layer + ".bias"])
        x += positions(len(x))
        for index in range(4):
            name = f"encoder.layers.{index}"
            z = normalize(x, name + ".norm1")
            x += attention(z, z, name + ".attn")
            x = feed(x, name, "norm2")
        x = normalize(x, "encoder.norm")
        cross = {}
        for index in range(4):
            key, value = keys(x, f"decoder.layers.{index}.cross_attn")
            cross[f"cross_k{index}"] = key
            cross[f"cross_v{index}"] = value
        return x, cross

    def decode(tokens, memory):
        x = weights["decoder.embed.weight"][tokens] * np.float32(16)
        x += positions(len(tokens))
        for index in range(4):
            name = f"decoder.layers.{index}"
            z = normalize(x, name + ".norm1")
            x += attention(z, z, name + ".self_attn", causal=True)
            x += attention(normalize(x, name + ".norm2"), memory, name + ".cross_attn")
            x = feed(x, name, "norm3")
        x = normalize(x, "decoder.norm")
        return (x @ weights["decoder.embed.weight"].T + weights["decoder.head.bias"])[-1]

    return encode, decode


def make_fixture(segments):
    points = []
    for start, end, count in segments:
        xy = np.linspace(start, end, count, dtype=np.float32)
        new = np.zeros((count, 6), dtype=np.float32)
        new[:, :2] = xy
        new[1:, 2:4] = np.diff(xy, axis=0)
        new[0, 4] = 1
        new[-1, 5] = 1
        points.append(new)
    return np.concatenate(points)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    directory = parser.parse_args().directory
    encode, decode = reference(directory)
    settings = ort.SessionOptions()
    settings.intra_op_num_threads = 2
    encoder = ort.InferenceSession(str(directory / "encoder.onnx"), settings, providers=["CPUExecutionProvider"])
    decoder = ort.InferenceSession(str(directory / "decoder.onnx"), settings, providers=["CPUExecutionProvider"])
    fixtures = [
        make_fixture([((-.75, -.5), (-.75, .5), 21), ((-.25, 0), (.25, 0), 11),
                      ((0, -.25), (0, .25), 11), ((.75, -.5), (.75, .5), 21)]),
        make_fixture([((-.5, -.5), (.5, .5), 29), ((.5, -.5), (-.5, .5), 29)]),
        make_fixture([((-.7, -.4), (.7, -.4), 29), ((-.7, .4), (.7, .4), 29)]),
    ]
    for index, points in enumerate(fixtures, 1):
        memory, expected_cross = encode(points)
        actual_cross = dict(zip([p.name for p in encoder.get_outputs()], encoder.run(None, {"points": points})))
        for key, expected in expected_cross.items():
            np.testing.assert_allclose(actual_cross[key], expected, atol=6e-4, rtol=1e-4)
        past = {f"past_{v}{i}": np.empty((8, 0, 32), np.float32) for i in range(4) for v in "kv"}
        tokens = [1]
        worst = 0.0
        for position in range(16):
            out = decoder.run(None, dict(actual_cross, **past,
                                         token=np.array([tokens[-1]], np.int64), position=np.array([position], np.int64)))
            expected = decode(tokens, memory)
            np.testing.assert_allclose(out[0][0], expected, atol=8e-4, rtol=1e-4)
            worst = max(worst, float(np.max(np.abs(out[0][0] - expected))))
            token = int(np.argmax(out[0][0]))
            assert token == int(np.argmax(expected))
            tokens.append(token)
            if token == 2:
                break
            past = dict(zip(past, out[1:]))
        print(f"Fixture {index}: {len(points)} input points, {len(tokens) - 1} matching decoder steps, max logit diff {worst:.2g}")


if __name__ == "__main__":
    main()
