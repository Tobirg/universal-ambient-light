/* MT5889 MTAL VSS parametric capture / Android 11 ARM32.
   --symbols: only dlopen and dlsym (no driver calls).
   --inspect-log: call native MTAL_Init in THIS process, then two getter ioctl
       wrappers: MTVSS_GetVssVideoInform and MTVSS_GetScreenCaptureMode.
       No GetConfig ioctl 0xC040291B (unsupported by this device).
   Optional --vss-init-home / --vss-init-hdmi performs MTVSS_Init(), a vendor
   initialization ioctl, before read-only getters; can alter VSS internal state.
   --connect0-hdmi / --connect0-home: explicit experimental ConnectEx(0) with a
   matching DisconnectEx(0) cleanup on normal completion. No StartCapture or buffers.
   Only use with unprotected HDMI desktop, not DRM material.
   Logging is done to fixed paths /data/local/tmp/mt5889_home.log and /data/local/tmp/mt5889_hdmi.log using raw write(2)
   before and after every vendor call, so ADB disconnect loses no log.
   Does not start/stop capture, program frame buffers, or change device perms.
*/
typedef unsigned int u32;
typedef unsigned char u8;
extern void *dlopen(const char *, int);
extern void *dlsym(void *, const char *);
extern char *dlerror(void);
#define RTLD_NOW 2
static long swrite(int fd,const void*p,u32 n) {
 register long r0 __asm__("r0")=fd;
 register const void*r1 __asm__("r1")=p;
 register u32 r2 __asm__("r2")=n;
 register long r7 __asm__("r7")=4;
 __asm__ volatile("svc #0":"+r"(r0):"r"(r1),"r"(r2),"r"(r7):"memory");
 return r0;
}
static long sopenat(const char*p,int flags,int mode) {
 register long r0 __asm__("r0")=-100;
 register const char*r1 __asm__("r1")=p;
 register long r2 __asm__("r2")=flags;
 register long r3 __asm__("r3")=mode;
 register long r7 __asm__("r7")=322;
 __asm__ volatile("svc #0":"+r"(r0):"r"(r1),"r"(r2),"r"(r3),"r"(r7):"memory");
 return r0;
}
/* mmap2 on ARM32: anonymous writable mapping solely owned by this process. */
static void *smmap(u32 size) {
 register long r0 __asm__("r0")=0;
 register long r1 __asm__("r1")=size;
 register long r2 __asm__("r2")=3;        /* PROT_READ | PROT_WRITE */
 register long r3 __asm__("r3")=0x22;     /* MAP_PRIVATE | MAP_ANONYMOUS */
 register long r4 __asm__("r4")=-1;
 register long r5 __asm__("r5")=0;
 register long r7 __asm__("r7")=192;      /* __NR_mmap2 */
 __asm__ volatile("svc #0":"+r"(r0):"r"(r1),"r"(r2),"r"(r3),"r"(r4),"r"(r5),"r"(r7):"memory");
 return (void*)r0;
}
static long sunlink(const char*path){register const char*r0 __asm__("r0")=path;register long r7 __asm__("r7")=10;
 __asm__ volatile("svc #0":"+r"(r0):"r"(r7):"memory");return (long)r0;}
static long sclose(long fd){register long r0 __asm__("r0")=fd;
 register long r7 __asm__("r7")=6;
 __asm__ volatile("svc #0":"+r"(r0):"r"(r7):"memory");return r0;}
/* ARM EABI raw syscalls for full detachment from adb shell/su transport. */
static long sfork(void){register long r0 __asm__("r0");register long r7 __asm__("r7")=2;
 __asm__ volatile("svc #0":"=r"(r0):"r"(r7):"memory");return r0;}
static long ssetsid(void){register long r0 __asm__("r0");register long r7 __asm__("r7")=66;
 __asm__ volatile("svc #0":"=r"(r0):"r"(r7):"memory");return r0;}
static long sdup2(long old,long target){register long r0 __asm__("r0")=old;register long r1 __asm__("r1")=target;register long r7 __asm__("r7")=63;
 __asm__ volatile("svc #0":"+r"(r0):"r"(r1),"r"(r7):"memory");return r0;}
__attribute__((noreturn)) static void sexit(int code){register long r0 __asm__("r0")=code;register long r7 __asm__("r7")=1;
 __asm__ volatile("svc #0"::"r"(r0),"r"(r7):"memory");for(;;){} }
static long outfd=1;
static u32 slen(const char*s){u32 i=0;while(s&&s[i])i++;return i;}
static void out(const char*s){if(s)swrite((int)outfd,s,slen(s));}
static int eq(const char*a,const char*b){while(*a&&*b){if(*a++!=*b++)return 0;}return *a==*b;}
static void hexn(u32 x,int n){const char*t="0123456789abcdef";char b[9];
 for(int i=0;i<n;i++)b[i]=t[(x>>((n-i-1)*4))&15];b[n]=0;out(b);}
static void hex(u32 x){out("0x");hexn(x,8);}
static void num(int x){unsigned int n=(unsigned int)x;if(x<0){out("-");n=0-n;}
 char b[12];int i=0;do{b[i++]='0'+n%10;n/=10;}while(n&&i<11);
 while(i--)swrite((int)outfd,b+i,1);}
/* CLOCK_MONOTONIC, ARM32 legacy clock_gettime. Timer only: no driver side effects. */
typedef struct {long tv_sec; long tv_nsec;} mono_ts;
static int monotime(mono_ts*t){
 register long r0 __asm__("r0")=1; /* CLOCK_MONOTONIC */
 register mono_ts*r1 __asm__("r1")=t;
 register long r7 __asm__("r7")=263; /* __NR_clock_gettime, ARM EABI */
 __asm__ volatile("svc #0":"+r"(r0):"r"(r1),"r"(r7):"memory");
 return (int)r0;
}
static long elapsed_ms(const mono_ts*a,const mono_ts*b){
 return (b->tv_sec-a->tv_sec)*1000L+(b->tv_nsec-a->tv_nsec)/1000000L;
}
static void timing(const char*name,const mono_ts*a,const mono_ts*b){
 out(name);out(" elapsed_ms=");num((int)elapsed_ms(a,b));out("\n");
}
static void sym(const char*n,void*p){out(n);out(" = ");if(p)hex((u32)p);else out("MISSING");out("\n");}
static void bytes(const u8*p,u32 n){for(u32 i=0;i<n;i++){
 if(i && (i%8==0))out("\n  ");else if(i)out(" ");hexn(p[i],2);}out("\n");}
static void zero(u8*p,u32 n){while(n--)*p++=0;}
static void put32(u8*p,u32 v){for(int i=0;i<4;i++)p[i]=(u8)(v>>(8*i));}
static void put64addr(u8*p,void*v){put32(p,(u32)v);put32(p+4,0);}
/* Strict decimal CLI parser; refuse negative values, overflow, garbage. */
static int dec(const char*s,u32*value){
 if(!s||!*s)return 0;u32 v=0;
 while(*s){if(*s<'0'||*s>'9')return 0;u32 digit=(u32)(*s++-'0');
  if(v>429496729u||(v==429496729u&&digit>5u))return 0;v=v*10u+digit;}
 *value=v;return 1;
}
/* Long enough delay to reduce risk when repeatedly driving a reverse-engineered CAP interface. */
static int sleep_ms(u32 milliseconds){
 struct {long sec; long nanosec;} req={milliseconds/1000u,(long)(milliseconds%1000u)*1000000L};
 register long r0 __asm__("r0")=(long)&req;
 register long r1 __asm__("r1")=0;
 register long r7 __asm__("r7")=162; /* ARM EABI __NR_nanosleep */
 __asm__ volatile("svc #0":"+r"(r0):"r"(r1),"r"(r7):"memory");
 return (int)r0;
}
static u32 hash_bytes(const u8*data,u32 size){
 u32 hash=2166136261u;
 for(u32 i=0;i<size;i++){hash^=data[i];hash*=16777619u;}
 return hash;
}
static long elapsed_us(const mono_ts*a,const mono_ts*b){
 return (b->tv_sec-a->tv_sec)*1000000L+(b->tv_nsec-a->tv_nsec)/1000L;
}
static void raw_output(const u8 *pix,u32 n){
 long fd=sopenat("/data/local/tmp/mt5889_frame.raw",577,0644);
 if(fd<0){out("FRAME: cannot create raw\n");return;}
 u32 total=0;
 while(total<n){long a=swrite((int)fd,pix+total,n-total);if(a<=0)break;total+=(u32)a;}
 sclose(fd);out("FRAME: RAW bytes written=");num((int)total);out("\n");
}
/* Freestanding ARM32 executable: provide software unsigned divide helper. */
__attribute__((used)) unsigned int __aeabi_uidiv(unsigned int dividend,unsigned int divisor){
 if(!divisor)return 0;
 unsigned int quotient=0;
 for(int bit=31;bit>=0;--bit){
   if((dividend>>bit)>=divisor){dividend-=divisor<<bit;quotient|=1u<<bit;}
 }
 return quotient;
}
static void usage(void){
 out("MT5889 parametric capture v2.3 (Android 11 ARM32)\n");
 out("Usage: mt5889_probe --capture X Y SRC_W SRC_H OUT_W OUT_H [--allow-4k]\n");
 out("       --bench X Y SRC_W SRC_H OUT_W OUT_H N [--allow-4k] [--period-ms MS]\n");
 out("       N=2..30; optional period-ms=25..200 (start-to-start target).\n");
 out("       Default: 80ms idle between captures; disconnect after each frame.\n");
 out("       --capture is one frame; --bench measures repeated capture call times.\n");
 out("       mt5889_probe --info-hdmi | --symbols | --help\n");
 out("Ex: --capture 0 0 1280 720 160 90 (tested 8:1)\n");
 out("Ex: --capture 0 0 1920 1080 160 90 (EXPERIMENTAL 12:1)\n");
 out("4K: --capture 0 0 3840 2160 480 270 --allow-4k (EXPERIMENTAL)\n");
 out("Files: /data/local/tmp/mt5889_hdmi.log, mt5889_frame.raw, mt5889_frame.bmp\n");
}
/* Top-down 32-bit BI_RGB BMP: MT5889 bytes appear as B,G,R,A. No conversion. */
static int save_bmp(const char*path,const u8*pix,u32 width,u32 height,u32 length){
 u8 b[54];zero(b,54);b[0]='B';b[1]='M';put32(b+2,54u+length);
 put32(b+10,54);put32(b+14,40);put32(b+18,width);put32(b+22,0u-height);
 b[26]=1;b[28]=32;put32(b+34,length);
 long fd=sopenat(path,577,0644);
 if(fd<0)return -1;
 if(swrite((int)fd,b,54)!=54){sclose(fd);return -2;}
 u32 total=0;
 while(total<length){long n=swrite((int)fd,pix+total,length-total);
  if(n<=0)break;total+=(u32)n;}
 sclose(fd);return total==length?0:-3;
}




/* Binary stdout: u32 LE width, u32 LE height, followed by RGB888 pixel bytes.
 * stderr: diagnostic text. One MTAL_Init/MTVSS_Init per process,
 * CaptureOne/DisconnectEx per frame. No double fork and no filesystem writes.
 */
static void eprint(const char *s){ long keep=outfd; outfd=2;out(s);outfd=keep; }
static void ecode(const char *s,int n){long keep=outfd;outfd=2;out(s);num(n);out("\n");outfd=keep;}
static int send_all(const u8*buf,u32 n){
 u32 done=0;while(done<n){long rc=swrite(1,buf+done,n-done);if(rc<=0)return 0;done+=(u32)rc;}return 1;
}
__attribute__((used,noinline)) static int entry(u32*sp){
 u32 argc=sp[0];const char**argv=(const char**)(sp+1);
 u32 x=0,y=0,srcw=0,srch=0,w=0,h=0,fps=0;
 if(argc!=8||!dec(argv[1],&x)||!dec(argv[2],&y)||!dec(argv[3],&srcw)||
   !dec(argv[4],&srch)||!dec(argv[5],&w)||!dec(argv[6],&h)||!dec(argv[7],&fps)){
   eprint("usage: libmt5889_vss_server.so X Y SRC_W SRC_H OUT_W OUT_H FPS\n");return 2;
 }
 if(srcw==0||srch==0||((unsigned long long)x+srcw)>0xffffffffull||((unsigned long long)y+srch)>0xffffffffull||
    w==0||h==0||((unsigned long long)srcw*h)!=((unsigned long long)srch*w)||
    fps==0||((unsigned long long)w*h)>((0xffffffffull-256ull)/4ull)){eprint("ERROR invalid geometry or fps\n");return 2;}
 const u32 pixlen=w*h*4u,rgblen=w*h*3u;
 u8*pix=(u8*)smmap(pixlen+256u),*rgb=(u8*)smmap(rgblen);
 if((u32)pix>=0xfffff001u||(u32)rgb>=0xfffff001u){eprint("mmap failed\n");return 3;}
 void*lib=dlopen("/vendor/lib/libmtal.so",RTLD_NOW);
 if(!lib){eprint("MTAL dlopen failed\n");return 4;}
 void*init=dlsym(lib,"MTAL_Init"),*vss=dlsym(lib,"MTVSS_Init"),
   *cap=dlsym(lib,"MTVSS_CaptureOne"),*disc=dlsym(lib,"MTVSS_DisconnectEx");
 if(!init||!vss||!cap||!disc){eprint("Missing MTAL symbol\n");return 5;}
 int st=((int(*)(void))init)();
 if(st){ecode("MTAL_Init error=",st);return 6;}
 st=((int(*)(void))vss)();
 if(st){ecode("MTVSS_Init error=",st);return 7;}
 eprint("MT5889 VSS stream started\n");
 u8 pkt[72],result[64],hdr[8];zero(pkt,72);zero(hdr,8);
 put32(pkt,x);put32(pkt+4,y);put32(pkt+8,srcw);put32(pkt+12,srch);
 put32(pkt+0x10,pixlen);put32(pkt+0x14,w);put32(pkt+0x20,0);
 put64addr(pkt+0x28,pix);put32(pkt+0x44,h);
 put32(hdr,w);put32(hdr+4,h);
 u32 frame=0,paced_frames=0;int consecutive_error=0;
 mono_ts origin={0},ts={0};monotime(&origin);
 while(1){
   for(u32 i=0;i<pixlen+256u;i++)pix[i]=0xa5;
   zero(result,64);
   int cr=((int(*)(u32,void*,void*))cap)(0,pkt,result);
   int ds=0;
   u32 changed=0,guard=0;
   if(cr==0&&ds==0){
     for(u32 i=0;i<pixlen;i++)if(pix[i]!=0xa5){changed=1;break;}
     for(u32 i=pixlen;i<pixlen+256u;i++)if(pix[i]!=0xa5){guard=1;break;}
   }
   if(cr||ds||!changed||guard){
     ecode("VSS capture ret=",cr);ecode("VSS disconnect ret=",ds);
     consecutive_error++;
     if(guard){eprint("Buffer overrun detected; fatal\n");return 9;}
     if(consecutive_error>=3){eprint("Too many capture errors; stopping\n");return 10;}
     sleep_ms(500);continue;
   }
   consecutive_error=0;
   for(u32 i=0,j=0;i<pixlen;i+=4){rgb[j++]=pix[i+2];rgb[j++]=pix[i+1];rgb[j++]=pix[i];}
   if(!send_all(hdr,8)||!send_all(rgb,rgblen)){eprint("Output pipe closed\n");((int(*)(u32))disc)(0);return 0;}
   frame++;paced_frames++;
   // Frame-start rate limit, avoids artificial busy-loop load.
   monotime(&ts);long age=elapsed_ms(&origin,&ts);
   if(paced_frames>=100000u){origin=ts;paced_frames=0;}
   long target=(long)((paced_frames*1000u)/fps);
   if(target>age)sleep_ms((u32)(target-age));
 }
}
__asm__(".global _start\n_start:\n mov r0, sp\n bl entry\n mov r7, #1\n svc #0\n");
