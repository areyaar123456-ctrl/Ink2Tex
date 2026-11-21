#!/usr/bin/env python3
"""Build reference ONNX graphs for Abel Notes ABLM v1 weights.

Requires numpy and onnx on the build machine. Graphs reference the pinned
original ABLM payload, so the Android app installs one copy of the weights.
The graph operations are an independent implementation of the model architecture.
"""
import argparse
import hashlib
import json
import math
import struct
from pathlib import Path

import numpy as np
import onnx
from onnx import TensorProto as T, external_data_helper, helper as h, numpy_helper

WEIGHTS = "math_ink.ablm"
SHA256 = "4e247ea7fa410fdccbfa349df53de5895c94da2b65ae6268c699871bee8b24b3"


class Graph:
    def __init__(self, header, payload):
        self.offsets = {tensor["name"]: tensor for tensor in header["tensors"]}
        self.payload = payload
        self.nodes = []
        self.constants = {}
        self.cast = {}
        self.names = 0
        self.dim = header["config"]["d_model"]
        self.heads = header["config"]["n_heads"]
        self.head_dim = self.dim // self.heads
        self.layers = header["config"]["n_decoder_layers"]
        self.vocab = len(header["vocab"])

    def tensor(self, name, data, dtype=None):
        if name not in self.constants:
            self.constants[name] = numpy_helper.from_array(np.asarray(data, dtype=dtype), name)
        return name

    def weight(self, name):
        if name not in self.cast:
            spec = self.offsets[name]
            assert spec["dtype"] == "f16"
            stored = T(name=name, data_type=T.FLOAT16, dims=spec["shape"])
            # onnx's helper requires raw_data to be present before setting metadata.
            stored.raw_data = b"x"
            external_data_helper.set_external_data(
                stored, WEIGHTS, offset=self.payload + spec["offset"], length=spec["bytes"]
            )
            stored.ClearField("raw_data")
            self.constants[name] = stored
            self.cast[name] = self.op("Cast", name, to=T.FLOAT)
        return self.cast[name]

    def op(self, kind, *inputs, name=None, **attrs):
        self.names += 1
        out = name or f"v{self.names}"
        self.nodes.append(h.make_node(kind, inputs, [out], **attrs))
        return out

    def linear(self, x, layer, bias=True, *, weight=None):
        matrix = self.weight(weight or layer + ".weight")
        x = self.op("MatMul", x, self.op("Transpose", matrix, perm=[1, 0]))
        return self.op("Add", x, self.weight(layer + ".bias")) if bias else x

    def norm(self, x, layer):
        return self.op("LayerNormalization", x, self.weight(layer + ".weight"),
                       self.weight(layer + ".bias"), axis=-1, epsilon=1e-5)

    def gelu(self, x):
        return self.op("Gelu", x, approximate="none")

    def mlp(self, x, layer, norm):
        y = self.linear(self.gelu(self.linear(self.norm(x, norm), layer + ".0")), layer + ".3")
        return self.op("Add", x, y)

    def project(self, x, name, bias=True):
        x = self.linear(x, name, bias)
        shape = self.tensor("split_shape", [-1, self.heads, self.head_dim], np.int64)
        return self.op("Transpose", self.op("Reshape", x, shape), perm=[1, 0, 2])

    def kv(self, x, layer):
        return (self.project(x, layer + ".k_proj", False), self.project(x, layer + ".v_proj"))

    def attention(self, q, key, value, layer):
        kt = self.op("Transpose", key, perm=[0, 2, 1])
        scale = self.tensor("attn_scale", math.sqrt(1 / self.head_dim), np.float32)
        scores = self.op("Mul", self.op("MatMul", q, kt), scale)
        weighted = self.op("MatMul", self.op("Softmax", scores, axis=-1), value)
        shape = self.tensor("join_shape", [-1, self.dim], np.int64)
        x = self.op("Reshape", self.op("Transpose", weighted, perm=[1, 0, 2]), shape)
        return self.linear(x, layer + ".out_proj")

    def positions(self):
        frequencies = np.exp(-np.log(10000.) * np.arange(self.dim // 2) / (self.dim // 2))
        angles = np.arange(1024)[:, None] * frequencies[None, :]
        return self.tensor("positions", np.concatenate([np.sin(angles), np.cos(angles)], axis=-1), np.float32)

    def save(self, name, inputs, outputs, directory):
        graph = h.make_graph(self.nodes, name, inputs, outputs, initializer=list(self.constants.values()))
        model = h.make_model(graph, ir_version=9, opset_imports=[h.make_opsetid("", 20)],
                             producer_name="Ink2TeX")
        onnx.save_model(model, directory / f"{name}.onnx")


def encoder(header, payload, directory):
    g = Graph(header, payload)
    x = g.op("Transpose", "points", perm=[1, 0])
    x = g.op("Unsqueeze", x, g.tensor("axis0", [0], np.int64))
    for index, stride in enumerate(header["config"]["stem_strides"]):
        name = f"encoder.stem.{index * 2}"
        x = g.gelu(g.op("Conv", x, g.weight(name + ".weight"), g.weight(name + ".bias"),
                        strides=[stride], pads=[2, 2]))
    x = g.op("Transpose", g.op("Squeeze", x, g.tensor("axis0", [0], np.int64)), perm=[1, 0])
    zero = g.tensor("zero", 0, np.int64)
    one = g.tensor("one", 1, np.int64)
    length = g.op("Gather", g.op("Shape", x), zero)
    indices = g.op("Range", zero, length, one)
    x = g.op("Add", x, g.op("Gather", g.positions(), indices))
    for index in range(header["config"]["n_encoder_layers"]):
        layer = f"encoder.layers.{index}"
        z = g.norm(x, layer + ".norm1")
        attn = layer + ".attn"
        k, v = g.kv(z, attn)
        x = g.op("Add", x, g.attention(g.project(z, attn + ".q_proj"), k, v, attn))
        x = g.mlp(x, layer + ".ff", layer + ".norm2")
    x = g.norm(x, "encoder.norm")
    outputs = []
    for index in range(g.layers):
        k, v = g.kv(x, f"decoder.layers.{index}.cross_attn")
        for name, value in [(f"cross_k{index}", k), (f"cross_v{index}", v)]:
            g.op("Identity", value, name=name)
            outputs.append(h.make_tensor_value_info(name, T.FLOAT, [g.heads, "E", g.head_dim]))
    g.save("encoder", [h.make_tensor_value_info("points", T.FLOAT, ["P", 6])], outputs, directory)


def decoder(header, payload, directory):
    g = Graph(header, payload)
    inputs = [h.make_tensor_value_info(key, T.INT64, [1]) for key in ["token", "position"]]
    outputs = [h.make_tensor_value_info("logits", T.FLOAT, [1, g.vocab])]
    x = g.op("Gather", g.weight("decoder.embed.weight"), "token")
    x = g.op("Add", g.op("Mul", x, g.tensor("embed_scale", math.sqrt(g.dim), np.float32)),
             g.op("Gather", g.positions(), "position"))
    for index in range(g.layers):
        layer = f"decoder.layers.{index}"
        self_attn = layer + ".self_attn"
        u = g.norm(x, layer + ".norm1")
        k, v = g.kv(u, self_attn)
        inputs.extend(h.make_tensor_value_info(f"{key}{index}", T.FLOAT, [g.heads, size, g.head_dim])
                      for key, size in [("past_k", "D"), ("past_v", "D"), ("cross_k", "E"), ("cross_v", "E")])
        k = g.op("Concat", f"past_k{index}", k, name=f"next_k{index}", axis=1)
        v = g.op("Concat", f"past_v{index}", v, name=f"next_v{index}", axis=1)
        outputs.extend(h.make_tensor_value_info(name, T.FLOAT, [g.heads, "O", g.head_dim]) for name in [k, v])
        x = g.op("Add", x, g.attention(g.project(u, self_attn + ".q_proj"), k, v, self_attn))
        cross = layer + ".cross_attn"
        q = g.project(g.norm(x, layer + ".norm2"), cross + ".q_proj")
        x = g.op("Add", x, g.attention(q, f"cross_k{index}", f"cross_v{index}", cross))
        x = g.mlp(x, layer + ".ff", layer + ".norm3")
    x = g.linear(g.norm(x, "decoder.norm"), "decoder.head", weight="decoder.embed.weight")
    g.op("Identity", x, name="logits")
    g.save("decoder", inputs, outputs, directory)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("weights", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    with args.weights.open("rb") as stream:
        assert hashlib.file_digest(stream, "sha256").hexdigest() == SHA256, "Unexpected Abel weights"
        stream.seek(0)
        assert stream.read(4) == b"ABLM"
        version, length = struct.unpack("<II", stream.read(8))
        assert version == 1
        header = json.loads(stream.read(length))
    args.output.mkdir(exist_ok=True, parents=True)
    encoder(header, 12 + length, args.output)
    decoder(header, 12 + length, args.output)
    (args.output / "vocab.json").write_text(json.dumps(header["vocab"]) + "\n")


if __name__ == "__main__":
    main()
