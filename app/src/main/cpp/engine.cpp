#include "engine.h"
#include "handwriting-head.h"
#include "cpu-projector.h"
#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"
#include <memory>
#include <mutex>
#include <stdexcept>
#include <vector>
#include <algorithm>
#include <chrono>
namespace ink {
std::atomic<bool> cancelled{false};
std::atomic<int> phase{0};
std::atomic<int> generated{0};
std::atomic<int64_t> input_tokens{0}, preprocessing_ms{0}, evaluation_ms{0}, generation_ms{0};
using Clock=std::chrono::steady_clock;
static int64_t milliseconds(Clock::time_point start) { return std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now()-start).count(); }
static std::mutex mutex;
static llama_model * model = nullptr;
static llama_context * context = nullptr;
static mtmd_context * vision = nullptr;
static std::unique_ptr<HandwritingHead> active_head;
static std::unique_ptr<HandwritingHead> candidate_head;
static std::unique_ptr<HandwritingHead> new_head() {
 if (!model || !context || !vision) throw std::runtime_error("No model loaded.");
 return std::make_unique<HandwritingHead>(llama_model_n_embd_out(model),
                                           llama_vocab_n_tokens(llama_model_get_vocab(model)));
}
struct HeadActivations {
 explicit HeadActivations(bool needed) { llama_set_embeddings_nextn(context, needed, true); }
 ~HeadActivations() { llama_set_embeddings_nextn(context, false, true); }
};
static void release() {
 active_head.reset();
 candidate_head.reset();
 if (vision) mtmd_free(vision);
 if (context) llama_free(context);
 if (model) llama_model_free(model);
 vision = nullptr; context = nullptr; model = nullptr;
}
static bool abort_fn(void *) { return cancelled.load(); }
static bool progress(float, void *) { return !cancelled.load(); }
void unload() { std::lock_guard<std::mutex> guard(mutex); phase=6; release(); phase=0; }
void load(const std::string & path, const std::string & projector, int threads, const std::string & backend_directory, int image_token_budget) {
 if(image_token_budget!=256 && image_token_budget!=1600)throw std::runtime_error("Unsupported image token budget.");
 std::lock_guard<std::mutex> guard(mutex);
 release();
 static std::once_flag once;
 std::call_once(once, [&]{
  if(!backend_directory.empty()) ggml_backend_load_all_from_path(backend_directory.c_str());
  llama_backend_init();
#ifdef __ANDROID__
  // The multimodal debug logger can print prompt text, including private examples.
  mtmd_log_set([](ggml_log_level, const char *, void *) {}, nullptr);
#endif
 });
 phase=1; generated=0;
 auto mp = llama_model_default_params();
 mp.n_gpu_layers = 0; mp.progress_callback = progress;
 model = llama_model_load_from_file(path.c_str(), mp);
 if (!model) throw std::runtime_error("Unable to load language model. Verify the model and available RAM.");
 try {
  auto cp = llama_context_default_params();
  cp.n_ctx = 8192; cp.n_batch = 512; cp.n_ubatch = 256;
  cp.n_threads = threads; cp.n_threads_batch = threads;
  cp.abort_callback = abort_fn;
  context = llama_init_from_model(model, cp);
  if (!context) throw std::runtime_error("Unable to allocate inference context.");
  std::string vision_path=projector;
#ifdef __ANDROID__
  phase=7;
  vision_path=cpu_projector(projector,cancelled);
#endif
  phase=2;
  auto vp = mtmd_context_params_default();
  vp.use_gpu = false; vp.n_threads = threads; vp.warmup = false;
  vp.image_min_tokens = image_token_budget==256 ? 64 : 196; vp.image_max_tokens = image_token_budget;
  vp.progress_callback = progress;
  vision = mtmd_init_from_file(vision_path.c_str(), model, vp);
  if (!vision || !mtmd_support_vision(vision)) throw std::runtime_error("Vision projector is incompatible with this runtime.");
 } catch (...) { release(); throw; }
}
static int tokenize_with_examples(mtmd_input_chunks * chunks, const mtmd_bitmap * target,
                                  const std::string & instruction, const std::vector<Example> & examples) {
 const std::string user = "<|im_start|>user\n";
 const std::string assistant = "<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n";
 const std::string end = "\n<|im_end|>\n";
 const std::string target_instruction = instruction +
     "\nThe earlier image and transcription pairs are examples of this writer's handwriting. "
     "Read the last image only. Transcribe exactly what it shows, preserving variables, indices, "
     "exponents, and signs even when the mathematics is wrong. Output only the last image's transcription.";
 const mtmd_input_text user_text{user.data(), user.size(), false, true};
 const mtmd_input_text assistant_text{assistant.data(), assistant.size(), false, true};
 const mtmd_input_text instruction_text{instruction.data(), instruction.size(), false, false};
 const mtmd_input_text target_text{target_instruction.data(), target_instruction.size(), false, false};
 const mtmd_input_text end_text{end.data(), end.size(), false, true};

 using Bitmap = std::unique_ptr<mtmd_bitmap, decltype(&mtmd_bitmap_free)>;
 std::vector<Bitmap> bitmaps;
 std::vector<mtmd_input_text> answers;
 answers.reserve(examples.size());
 std::vector<mtmd_input_part> parts;
 for (const auto & example : examples) {
  bitmaps.emplace_back(mtmd_bitmap_init(example.width, example.height, example.rgb.data()), mtmd_bitmap_free);
  if (!bitmaps.back()) throw std::runtime_error("Could not prepare handwriting example.");
  // Parse the reviewed transcription as ordinary text, never as chat or image markers.
  answers.push_back({example.transcription.data(), example.transcription.size(), false, false});
  parts.insert(parts.end(), {{&user_text, nullptr}, {nullptr, bitmaps.back().get()},
      {&instruction_text, nullptr}, {&assistant_text, nullptr}, {&answers.back(), nullptr},
      {&end_text, nullptr}});
 }
 parts.insert(parts.end(), {{&user_text, nullptr}, {nullptr, target},
     {&target_text, nullptr}, {&assistant_text, nullptr}});
 std::vector<const mtmd_input_part *> inputs;
 for (const auto & part : parts) inputs.push_back(&part);
 return mtmd_tokenize_from_parts(vision, chunks, inputs.data(), inputs.size(), true);
}

static llama_pos evaluate_image(const unsigned char * rgb, int width, int height,
                              const std::string & instruction, int max_tokens,
                              const std::vector<Example> & examples) {
 if (width < 1 || height < 1 || (int64_t)width*height > 4000000) throw std::runtime_error("Image exceeds the safe pixel budget.");
 phase=3; generated=0;
 input_tokens=0;preprocessing_ms=0;evaluation_ms=0;generation_ms=0;
 const auto prep_start=Clock::now();
 llama_memory_clear(llama_get_memory(context), true);
 // The packaged integration is Qwen3.5 OCR only. Explicit non-thinking template
 // matches the upstream OvisOCR2 and LightOnOCR-3 processor templates.
 const std::string formatted = std::string("<|im_start|>user\n") + mtmd_default_marker() + instruction +
  "<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n";
 std::unique_ptr<mtmd_bitmap, decltype(&mtmd_bitmap_free)> bitmap(mtmd_bitmap_init(width,height,rgb), mtmd_bitmap_free);
 std::unique_ptr<mtmd_input_chunks, decltype(&mtmd_input_chunks_free)> chunks(mtmd_input_chunks_init(), mtmd_input_chunks_free);
 const mtmd_bitmap * bitmaps[] = {bitmap.get()};
 mtmd_input_text text{formatted.data(), formatted.size(), true, true};
 const int tokenized = examples.empty()
    ? mtmd_tokenize(vision,chunks.get(),&text,bitmaps,1)
    : tokenize_with_examples(chunks.get(), bitmap.get(), instruction, examples);
 if (tokenized) throw std::runtime_error("Vision preprocessing failed.");
 const auto tokens = mtmd_helper_get_n_tokens(chunks.get());
 input_tokens=tokens;preprocessing_ms=milliseconds(prep_start);
 if (tokens + max_tokens >= llama_n_ctx(context)) throw std::runtime_error("Page exceeds context budget. Select a smaller region.");
 if (cancelled) throw std::runtime_error("Conversion cancelled.");
 phase=4;
 const auto eval_start=Clock::now();
 llama_pos past = 0;
 if (mtmd_helper_eval_chunks(vision,context,chunks.get(),0,0,512,true,&past)) throw std::runtime_error(cancelled ? "Conversion cancelled." : "Image evaluation failed.");
 evaluation_ms=milliseconds(eval_start);
 return past;
}

Result recognize(const unsigned char * rgb, int width, int height, const std::string & instruction,
                 int max_tokens, const std::vector<Example> & examples, int head_mode) {
 std::lock_guard<std::mutex> guard(mutex);
 if (!model || !context || !vision) throw std::runtime_error("No model loaded.");
 const auto * head = head_mode == 2 ? candidate_head.get() : head_mode == 1 ? active_head.get() : nullptr;
 const bool apply_head = head && !head->empty();
 const HeadActivations activations(apply_head);
 auto past = evaluate_image(rgb, width, height, instruction, max_tokens, examples);
 std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(llama_sampler_init_greedy(),llama_sampler_free);
 const llama_vocab * vocab = llama_model_get_vocab(model);
 phase=5;
 const auto generate_start=Clock::now();
 Result result{"",0,false};
 auto batch = llama_batch_init(1,0,1);
 // M-RoPE token positions have four coordinates, all equal for generated text.
 if (mtmd_decode_use_mrope(vision)) { free(batch.pos); batch.pos = (llama_pos *)calloc(4,sizeof(llama_pos)); }
 try {
  for (int i=0;i<max_tokens;i++) {
   if (cancelled) throw std::runtime_error("Conversion cancelled.");
   if (apply_head) {
    const auto * hidden = llama_get_embeddings_nextn_ith(context, -1);
    auto * logits = llama_get_logits_ith(context, -1);
    if (!hidden || !logits) throw std::runtime_error("The model did not provide handwriting activations.");
    head->apply(hidden, logits);
   }
   llama_token token = llama_sampler_sample(sampler.get(),context,-1);
   if (llama_vocab_is_eog(vocab,token)) { generation_ms=milliseconds(generate_start);llama_batch_free(batch); return result; }
   std::vector<char> piece(256);
   int n = llama_token_to_piece(vocab,token,piece.data(),piece.size(),0,false);
   if (n < 0) { piece.resize(-n); n = llama_token_to_piece(vocab,token,piece.data(),piece.size(),0,false); }
   if (n > 0) result.text.append(piece.data(),n);
   result.tokens++; generated=result.tokens;
   if (past+1 >= (int)llama_n_ctx(context)) { result.truncated = true; break; }
   batch.n_tokens = 1; batch.token[0] = token; batch.n_seq_id[0] = 1; batch.seq_id[0][0] = 0; batch.logits[0] = true;
   int coords = mtmd_decode_use_mrope(vision) ? 4 : 1;
   for(int c=0;c<coords;c++) batch.pos[c] = past;
   if (llama_decode(context,batch)) throw std::runtime_error(cancelled ? "Conversion cancelled." : "Token decoding failed.");
   past++;
  }
  result.truncated = true;
 } catch (...) { llama_batch_free(batch); throw; }
 generation_ms=milliseconds(generate_start);llama_batch_free(batch); return result;
}
void load_head(const std::string & path) {
 std::lock_guard<std::mutex> guard(mutex);
 auto head = new_head();
 head->load(path);
 active_head = std::move(head);
}

void begin_training() {
 std::lock_guard<std::mutex> guard(mutex);
 candidate_head = new_head();
}

void train_sample(const unsigned char * rgb, int width, int height,
                  const std::string & prompt, const std::string & transcription) {
 std::lock_guard<std::mutex> guard(mutex);
 if (!candidate_head) throw std::runtime_error("Start a training batch first.");
 const auto * vocab = llama_model_get_vocab(model);
 std::vector<llama_token> tokens(768);
 // The supplied source is literal text. Chat special tokens are never interpreted.
 const int count = llama_tokenize(vocab, transcription.data(), transcription.size(),
                                  tokens.data(), tokens.size(), false, false);
 if (count < 1) throw std::runtime_error("Use a shorter labeled snip (up to 768 model tokens).");
 tokens.resize(count);
 const auto eos = llama_vocab_eos(vocab);
 if (eos < 0) throw std::runtime_error("The model has no end-of-transcription token.");
 tokens.push_back(eos);

 // Masked NextN output exposes the final normalized decoder state without outputting every prompt token.
 const HeadActivations activations(true);
 auto position = evaluate_image(rgb, width, height, prompt, tokens.size(), {});
 candidate_head->add(llama_get_embeddings_nextn_ith(context, -1),
                     llama_get_logits_ith(context, -1), tokens.front());

 // Teacher-force checked tokens in small blocks to bound the full-vocabulary output buffer.
 std::unique_ptr<llama_batch_ext, decltype(&llama_batch_ext_free)> batch(
     llama_batch_ext_init(context), llama_batch_ext_free);
 if (!batch) throw std::runtime_error("Unable to allocate a training batch.");
 phase = 8;
 for (int start = 0; start < count; start += 12) {
    if (cancelled) throw std::runtime_error("Training cancelled.");
    llama_batch_ext_clear(batch.get());
    const int end = std::min(start + 12, count);
    for (int index = start; index < end; ++index) {
        const int row = llama_batch_ext_add_token(batch.get(), 0, tokens[index]);
        if (row < 0 || !llama_batch_ext_set_pos(batch.get(), row, &position)
            || !llama_batch_ext_set_output_logits(batch.get(), row, true)) {
            throw std::runtime_error("Unable to prepare a checked token.");
        }
        ++position;
    }
    if (llama_process(context, LLAMA_PROCESS_TYPE_DECODE, batch.get())) {
        throw std::runtime_error(cancelled ? "Training cancelled." : "Unable to read training activations.");
    }
    for (int index = start; index < end; ++index) {
        candidate_head->add(llama_get_embeddings_nextn_ith(context, index - start),
                            llama_get_logits_ith(context, index - start), tokens[index + 1]);
    }
    generated = end;
 }
}

int fit_training() {
 std::lock_guard<std::mutex> guard(mutex);
 if (!candidate_head) throw std::runtime_error("Start a training batch first.");
 phase = 9;
 const auto count = candidate_head->training_tokens();
 candidate_head->fit(cancelled);
 return candidate_head->empty() ? 0 : int(count);
}

void save_candidate(const std::string & path) {
 std::lock_guard<std::mutex> guard(mutex);
 if (!candidate_head || candidate_head->empty()) throw std::runtime_error("No changed weights to save.");
 candidate_head->save(path);
}

}
