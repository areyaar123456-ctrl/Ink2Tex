#include <jni.h>
#include "engine.h"
#include "llama.h"
#include <vector>
#include <stdexcept>
static std::string str(JNIEnv * e,jstring s) { const char * p=e->GetStringUTFChars(s,nullptr); std::string r(p);e->ReleaseStringUTFChars(s,p);return r; }
static void fail(JNIEnv *e,const std::exception &x) { e->ThrowNew(e->FindClass("java/lang/IllegalStateException"),x.what()); }
extern "C" JNIEXPORT void JNICALL Java_dev_ink2tex_ocr_NativeBridge_load(JNIEnv*e,jobject,jstring m,jstring v,jint t,jstring directory,jint budget) { try { ink::load(str(e,m),str(e,v),t,str(e,directory),budget); } catch(const std::exception&x){fail(e,x);} }
static std::vector<unsigned char> bytes(JNIEnv * env, jbyteArray array) {
 const auto size = env->GetArrayLength(array);
 std::vector<unsigned char> result(size);
 env->GetByteArrayRegion(array, 0, size, reinterpret_cast<jbyte *>(result.data()));
 return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_dev_ink2tex_ocr_NativeBridge_recognize(
    JNIEnv * env, jobject, jbyteArray rgb, jint width, jint height, jstring prompt, jint max_tokens,
    jobjectArray pixels, jintArray widths, jintArray heights, jobjectArray transcriptions, jint headMode) {
 auto * e = env;
 try {
  if (width < 1 || height < 1 || int64_t(width) * height > 4000000 ||
      int64_t(width) * height * 3 != e->GetArrayLength(rgb)) {
   throw std::runtime_error("Invalid RGB buffer.");
  }
  const auto count = e->GetArrayLength(pixels);
  if (count > 2 || count != e->GetArrayLength(transcriptions) ||
      count != e->GetArrayLength(widths) || count != e->GetArrayLength(heights)) {
   throw std::runtime_error("Invalid handwriting examples.");
  }
  jint w[2] = {}, h[2] = {};
  if (count) {
   e->GetIntArrayRegion(widths, 0, count, w);
   e->GetIntArrayRegion(heights, 0, count, h);
  }
  std::vector<ink::Example> examples;
  for (int index = 0; index < count; ++index) {
   auto image = static_cast<jbyteArray>(e->GetObjectArrayElement(pixels, index));
   auto text = static_cast<jbyteArray>(e->GetObjectArrayElement(transcriptions, index));
   if (!image || !text || w[index] < 1 || h[index] < 1 || w[index] > 512 || h[index] > 512 ||
       w[index] * h[index] * 3 != e->GetArrayLength(image) ||
       e->GetArrayLength(text) < 1 || e->GetArrayLength(text) > 1600) {
    throw std::runtime_error("Handwriting example exceeds the safe input budget.");
   }
   auto utf8 = bytes(e, text);
   examples.push_back({bytes(e, image), w[index], h[index], std::string(utf8.begin(), utf8.end())});
   e->DeleteLocalRef(image);
   e->DeleteLocalRef(text);
  }
  auto target = bytes(e, rgb);
  auto result = ink::recognize(target.data(), width, height, str(e, prompt), max_tokens, examples, headMode);
  std::string payload=std::to_string(result.tokens)+"\n"+(result.truncated ? "1\n":"0\n")+result.text;
  auto out=e->NewByteArray(payload.size());e->SetByteArrayRegion(out,0,payload.size(),(const jbyte*)payload.data());return out;
 } catch(const std::exception&x){fail(e,x);return nullptr;}
}
extern "C" JNIEXPORT void JNICALL Java_dev_ink2tex_ocr_NativeBridge_cancel(JNIEnv*,jobject) { ink::cancelled=true; }
extern "C" JNIEXPORT void JNICALL Java_dev_ink2tex_ocr_NativeBridge_unload(JNIEnv*,jobject) { ink::unload(); }

extern "C" JNIEXPORT void JNICALL Java_dev_ink2tex_ocr_NativeBridge_prepare(JNIEnv*,jobject) { ink::cancelled=false; }

extern "C" JNIEXPORT jintArray JNICALL Java_dev_ink2tex_ocr_NativeBridge_status(JNIEnv*e,jobject) {
 jint values[]={ink::phase.load(),ink::generated.load()};auto out=e->NewIntArray(2);if(out)e->SetIntArrayRegion(out,0,2,values);return out;
}

extern "C" JNIEXPORT jstring JNICALL Java_dev_ink2tex_ocr_NativeBridge_systemInfo(JNIEnv*e,jobject) { return e->NewStringUTF(llama_print_system_info()); }

extern "C" JNIEXPORT jlongArray JNICALL Java_dev_ink2tex_ocr_NativeBridge_timings(JNIEnv*e,jobject) {
 jlong values[]={ink::input_tokens.load(),ink::preprocessing_ms.load(),ink::evaluation_ms.load(),ink::generation_ms.load()};
 auto out=e->NewLongArray(4);if(out)e->SetLongArrayRegion(out,0,4,values);return out;
}


extern "C" JNIEXPORT void JNICALL Java_dev_ink2tex_ocr_NativeBridge_loadHead(JNIEnv * e, jobject, jstring path) {
 try { ink::load_head(str(e, path)); } catch (const std::exception & error) { fail(e, error); }
}
extern "C" JNIEXPORT void JNICALL Java_dev_ink2tex_ocr_NativeBridge_beginTraining(JNIEnv * e, jobject) {
 try { ink::begin_training(); } catch (const std::exception & error) { fail(e, error); }
}
extern "C" JNIEXPORT void JNICALL Java_dev_ink2tex_ocr_NativeBridge_trainSample(
    JNIEnv * e, jobject, jbyteArray rgb, jint width, jint height, jstring prompt, jbyteArray target) {
 try {
    if (width < 1 || height < 1 || int64_t(width) * height > 4000000 ||
        int64_t(width) * height * 3 != e->GetArrayLength(rgb) ||
        e->GetArrayLength(target) < 1 || e->GetArrayLength(target) > 12000) {
        throw std::runtime_error("Invalid labeled image.");
    }
    auto pixels = bytes(e, rgb);
    auto answer = bytes(e, target);
    ink::train_sample(pixels.data(), width, height, str(e, prompt), std::string(answer.begin(), answer.end()));
 } catch (const std::exception & error) { fail(e, error); }
}
extern "C" JNIEXPORT jint JNICALL Java_dev_ink2tex_ocr_NativeBridge_fitTraining(JNIEnv * e, jobject) {
 try { return ink::fit_training(); } catch (const std::exception & error) { fail(e, error); return 0; }
}
extern "C" JNIEXPORT void JNICALL Java_dev_ink2tex_ocr_NativeBridge_saveCandidate(JNIEnv * e, jobject, jstring path) {
 try { ink::save_candidate(str(e, path)); } catch (const std::exception & error) { fail(e, error); }
}
