#include <jni.h>
#include "engine.h"
#include "llama.h"
#include <vector>
#include <stdexcept>
static std::string str(JNIEnv * e,jstring s) { const char * p=e->GetStringUTFChars(s,nullptr); std::string r(p);e->ReleaseStringUTFChars(s,p);return r; }
static void fail(JNIEnv *e,const std::exception &x) { e->ThrowNew(e->FindClass("java/lang/IllegalStateException"),x.what()); }
extern "C" JNIEXPORT void JNICALL Java_dev_ink2tex_ocr_NativeBridge_load(JNIEnv*e,jobject,jstring m,jstring v,jint t,jstring directory,jint budget) { try { ink::load(str(e,m),str(e,v),t,str(e,directory),budget); } catch(const std::exception&x){fail(e,x);} }
extern "C" JNIEXPORT jbyteArray JNICALL Java_dev_ink2tex_ocr_NativeBridge_recognize(JNIEnv*e,jobject,jbyteArray rgb,jint w,jint h,jstring p,jint max) {
 try {
  auto size=e->GetArrayLength(rgb);
  if ((int64_t)w*h*3 != size) throw std::runtime_error("Invalid RGB buffer.");
  std::vector<unsigned char> bytes(size);e->GetByteArrayRegion(rgb,0,size,(jbyte*)bytes.data());
  auto result=ink::recognize(bytes.data(),w,h,str(e,p),max);
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
