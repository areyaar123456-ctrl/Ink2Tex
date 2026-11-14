#include "cpu-projector.h"
#include "gguf.h"
#include "ggml.h"
#include <algorithm>
#include <filesystem>
#include <fstream>
#include <memory>
#include <vector>
#include <stdexcept>
#include <cstdint>
#include <fcntl.h>
#include <unistd.h>

namespace {
using FileContext=std::unique_ptr<gguf_context,decltype(&gguf_free)>;
size_t serialized_size(const gguf_context * ctx) {
 const auto n=gguf_get_n_tensors(ctx);
 if(!n)return gguf_get_meta_size(ctx);
 const size_t alignment=gguf_get_alignment(ctx);
 const size_t end=gguf_get_tensor_offset(ctx,n-1)+gguf_get_tensor_size(ctx,n-1);
 return gguf_get_meta_size(ctx)+(end+alignment-1)/alignment*alignment;
}
}
std::string cpu_projector(const std::string & source,const std::atomic<bool> & cancelled) {
 namespace fs=std::filesystem;
 ggml_context * metadata_raw=nullptr;
 FileContext input(gguf_init_from_file(source.c_str(),{true,&metadata_raw}),gguf_free);
 std::unique_ptr<ggml_context,decltype(&ggml_free)> metadata(metadata_raw,ggml_free);
 if(!input || !metadata)throw std::runtime_error("Cannot read vision-model metadata");
 const auto count=gguf_get_n_tensors(input.get());
 bool has_bf16=false;
 for(int64_t i=0;i<count;i++)has_bf16|=gguf_get_tensor_type(input.get(),i)==GGML_TYPE_BF16;
 if(!has_bf16)return source;
 const auto source_size=fs::file_size(source);
 const auto stamp=fs::last_write_time(source).time_since_epoch().count();
 const std::string target=source+".cpu-f32-v1.gguf";
 if(fs::exists(target)) {
  FileContext cached(gguf_init_from_file(target.c_str(),{true,nullptr}),gguf_free);
  if(cached) {
   auto size_key=gguf_find_key(cached.get(),"ink2tex.source_size");
   auto time_key=gguf_find_key(cached.get(),"ink2tex.source_time");
   if(size_key>=0 && time_key>=0 && gguf_get_kv_type(cached.get(),size_key)==GGUF_TYPE_UINT64 && gguf_get_kv_type(cached.get(),time_key)==GGUF_TYPE_INT64 && gguf_get_val_u64(cached.get(),size_key)==source_size && gguf_get_val_i64(cached.get(),time_key)==stamp && fs::file_size(target)==serialized_size(cached.get()))return target;
  }
 }
 FileContext output(gguf_init_empty(),gguf_free);
 gguf_set_kv(output.get(),input.get());
 gguf_set_val_u64(output.get(),"ink2tex.source_size",source_size);
 gguf_set_val_i64(output.get(),"ink2tex.source_time",stamp);
 for(int64_t i=0;i<count;i++) {
  const char * name=gguf_get_tensor_name(input.get(),i);
  gguf_add_tensor(output.get(),ggml_get_tensor(metadata.get(),name));
  if(gguf_get_tensor_type(input.get(),i)==GGML_TYPE_BF16)gguf_set_tensor_type(output.get(),name,GGML_TYPE_F32);
 }
 if(fs::space(fs::path(source).parent_path()).available<serialized_size(output.get())+64*1024*1024)throw std::runtime_error("Free more storage for the CPU vision cache");
 const std::string temporary=target+".tmp";
 try {
  if(!gguf_write_to_file(output.get(),temporary.c_str(),true))throw std::runtime_error("Cannot create CPU vision cache");
  std::ifstream from(source,std::ios::binary);
  std::ofstream to(temporary,std::ios::binary|std::ios::app);
  std::vector<uint16_t> small(65536);
  std::vector<uint32_t> expanded(65536);
  std::vector<char> bytes(131072);
  const size_t alignment=gguf_get_alignment(output.get());
  if(alignment>4096)throw std::runtime_error("Unsupported vision-model alignment");
  const std::vector<char> zeros(alignment,0);
  for(int64_t i=0;i<count;i++) {
   from.seekg(gguf_get_data_offset(input.get())+gguf_get_tensor_offset(input.get(),i));
   size_t remaining=gguf_get_tensor_size(input.get(),i);
   const bool convert=gguf_get_tensor_type(input.get(),i)==GGML_TYPE_BF16;
   while(remaining) {
    if(cancelled.load())throw std::runtime_error("Conversion cancelled");
    const size_t n=std::min(remaining,bytes.size());
    if(convert) {
     from.read(reinterpret_cast<char*>(small.data()),n);
     for(size_t j=0;j<n/2;j++)expanded[j]=uint32_t(small[j])<<16;
     to.write(reinterpret_cast<const char*>(expanded.data()),n*2);
    } else { from.read(bytes.data(),n);to.write(bytes.data(),n); }
    if(!from || !to)throw std::runtime_error("CPU vision cache I/O failed");
    remaining-=n;
   }
   const auto position=static_cast<size_t>(to.tellp());
   const auto padding=(alignment-position%alignment)%alignment;
   to.write(zeros.data(),padding);
  }
  to.close();if(!to)throw std::runtime_error("Cannot finish CPU vision cache");
  if(fs::file_size(temporary)!=serialized_size(output.get()))throw std::runtime_error("Incomplete CPU vision cache");
  int fd=open(temporary.c_str(),O_RDONLY);
  if(fd<0)throw std::runtime_error("Cannot sync CPU vision cache");
  const auto synced=fsync(fd);close(fd);
  if(synced!=0)throw std::runtime_error("Cannot sync CPU vision cache");
  fs::rename(temporary,target);
  return target;
 } catch(...) { std::error_code error;fs::remove(temporary,error);throw; }
}
