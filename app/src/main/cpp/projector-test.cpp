#include "cpu-projector.h"
#include "ggml.h"
#include "gguf.h"
#include <filesystem>
#include <fstream>
#include <iostream>
#include <memory>
#include <vector>
#include <cstdint>
#include <stdexcept>
static void check(bool ok,const char * message) { if(!ok)throw std::runtime_error(message); }
static std::vector<char> contents(const std::string & file) { std::ifstream f(file,std::ios::binary);return {(std::istreambuf_iterator<char>(f)),{}}; }
int main(int argc,char ** argv) {
 try {
  std::filesystem::path directory=argc>1?argv[1]:"/tmp/ink2tex-projector-test";
  std::filesystem::create_directories(directory);
  const std::string original=(directory/"fixture.gguf").string();
  auto * ctx=ggml_init({4096,nullptr,false});
  auto * a=ggml_new_tensor_1d(ctx,GGML_TYPE_BF16,32);ggml_set_name(a,"vision.weight");
  const uint16_t values[]={0,0x8000,0x3f80,0xbf80,0x0001,0x7f80,0xff80,0x7fc1};
  for(int i=0;i<32;i++)static_cast<uint16_t*>(a->data)[i]=values[i%8];
  auto * b=ggml_new_tensor_1d(ctx,GGML_TYPE_F32,8);ggml_set_name(b,"vision.norm");
  for(int i=0;i<8;i++)static_cast<float*>(b->data)[i]=i*0.25f-1.f;
  auto * file=gguf_init_empty();gguf_set_val_str(file,"general.architecture","clip");
  const uint32_t ids[]={11,22,33};gguf_set_arr_data(file,"fixture.ids",GGUF_TYPE_UINT32,ids,3);
  gguf_add_tensor(file,a);gguf_add_tensor(file,b);
  check(gguf_write_to_file(file,original.c_str(),false),"fixture write failed");gguf_free(file);
  const auto before=contents(original);
  std::atomic<bool> cancelled{false};
  const auto result=cpu_projector(original,cancelled);
  ggml_context * loaded_raw=nullptr;
  auto * loaded=gguf_init_from_file(result.c_str(),{false,&loaded_raw});
  check(loaded && loaded_raw,"converted GGUF could not load");
  check(gguf_get_tensor_type(loaded,0)==GGML_TYPE_F32,"BF16 tensor not expanded");
  auto * weights=ggml_get_tensor(loaded_raw,"vision.weight");
  for(int i=0;i<32;i++)check(static_cast<uint32_t*>(weights->data)[i]==uint32_t(values[i%8])<<16,"BF16 value bits changed");
  auto * norm=ggml_get_tensor(loaded_raw,"vision.norm");
  for(int i=0;i<8;i++)check(static_cast<float*>(norm->data)[i]==i*0.25f-1.f,"original F32 value changed");
  check(std::string(gguf_get_val_str(loaded,gguf_find_key(loaded,"general.architecture")))=="clip","processor metadata changed");
  check(gguf_get_arr_n(loaded,gguf_find_key(loaded,"fixture.ids"))==3,"array metadata changed");
  gguf_free(loaded);ggml_free(loaded_raw);
  check(contents(original)==before,"original download was modified");
  const auto stamp=std::filesystem::last_write_time(result);
  check(cpu_projector(original,cancelled)==result && std::filesystem::last_write_time(result)==stamp,"cache was not reused");
  std::filesystem::remove(result);cancelled=true;bool stopped=false;
  try {cpu_projector(original,cancelled);}catch(const std::runtime_error &e){stopped=std::string(e.what()).find("cancelled")!=std::string::npos;}
  check(stopped && !std::filesystem::exists(result+".tmp"),"cancel left a partial cache");
  check(contents(original)==before,"cancel changed original model");
  cancelled=false;check(std::filesystem::exists(cpu_projector(original,cancelled)),"retry failed");
  ggml_free(ctx);
  std::cout<<"PASS: exact BF16 expansion, untouched F32 and processor metadata, original preservation, cache reuse, cancellation cleanup and retry\n";
  return 0;
 }catch(const std::exception &e){std::cerr<<e.what()<<"\n";return 1;}
}
