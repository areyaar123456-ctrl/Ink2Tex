#include <jni.h>
#include "engine.h"
#include <vector>
#include <stdexcept>
static std::string str(JNIEnv * e,jstring s) { const char * p=e->GetStringUTFChars(s,nullptr); std::string r(p);e->ReleaseStringUTFChars(s,p);return r; }
static void fail(JNIEnv *e,const std::exception &x) { e->ThrowNew(e->FindClass("java/lang/IllegalStateException"),x.what()); }
extern "C" JNIEXPORT void JNICALL Java_dev_ink2tex_ocr_NativeBridge_load(JNIEnv*e,jobject,jstring m,jstring v,jint t) { try { ink::load(str(e,m),str(e,v),t); } catch(const std::exception&x){fail(e,x);} }
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
