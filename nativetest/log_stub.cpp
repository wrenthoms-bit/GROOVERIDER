#include <cstdarg>
#include <cstdio>
extern "C" int __android_log_print(int, const char* tag, const char* fmt, ...) {
    va_list a; va_start(a, fmt); vprintf(fmt, a); va_end(a); printf("\n"); return 0;
}
