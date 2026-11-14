#include "engine.h"
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
static void release() {
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
Result recognize(const unsigned char * rgb, int width, int height, const std::string & instruction, int max_tokens) {
 std::lock_guard<std::mutex> guard(mutex);
 if (!model || !context || !vision) throw std::runtime_error("No model loaded.");
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
 if (mtmd_tokenize(vision,chunks.get(),&text,bitmaps,1)) throw std::runtime_error("Vision preprocessing failed.");
 const auto tokens = mtmd_helper_get_n_tokens(chunks.get());
 input_tokens=tokens;preprocessing_ms=milliseconds(prep_start);
 if (tokens + max_tokens >= llama_n_ctx(context)) throw std::runtime_error("Page exceeds context budget. Select a smaller region.");
 if (cancelled) throw std::runtime_error("Conversion cancelled.");
 phase=4;
 const auto eval_start=Clock::now();
 llama_pos past = 0;
 if (mtmd_helper_eval_chunks(vision,context,chunks.get(),0,0,512,true,&past)) throw std::runtime_error(cancelled ? "Conversion cancelled." : "Image evaluation failed.");
 evaluation_ms=milliseconds(eval_start);
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
}
