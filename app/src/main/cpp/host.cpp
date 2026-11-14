#include "engine.h"
#include "stb_image.h"
#include <iostream>
#include <fstream>
#include <chrono>
int main(int argc,char ** argv) {
 if(argc!=5){std::cerr<<"model projector image prompt-file\n";return 2;}
 try {
  std::ifstream input(argv[4]);std::string prompt((std::istreambuf_iterator<char>(input)),{});
  auto start=std::chrono::steady_clock::now();ink::load(argv[1],argv[2],3);
  int w,h,c;auto rgb=stbi_load(argv[3],&w,&h,&c,3);if(!rgb)throw std::runtime_error("Image failed");
  auto r=ink::recognize(rgb,w,h,prompt,2048);stbi_image_free(rgb);
  std::cout<<r.text<<"\n";std::cerr<<"TOKENS="<<r.tokens<<" TRUNCATED="<<r.truncated<<" SECONDS="<<std::chrono::duration<double>(std::chrono::steady_clock::now()-start).count()<<"\n";
  ink::unload();return r.truncated?3:0;
 }catch(const std::exception&x){std::cerr<<x.what()<<"\n";return 1;}
}
