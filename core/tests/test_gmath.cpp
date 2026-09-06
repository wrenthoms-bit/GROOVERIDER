#include "gmath.h"
#include <cmath>
#include <cstdio>
using namespace grv;
int main(){
    double me_exp=0, me_sin=0, me_tanh=0, me_exp2=0;
    for(int i=0;i<200000;i++){
        float x = -10.f + 20.f*i/200000.f;
        me_exp = fmax(me_exp, fabs(gexp(x)-expf(x))/(expf(x)+1e-9));
        me_sin = fmax(me_sin, fabs(gsin(x)-sinf(x)));
        me_tanh= fmax(me_tanh,fabs(gtanh(x)-tanhf(x)));
    }
    for(int i=0;i<200000;i++){ float x=-24.f+48.f*i/200000.f; me_exp2=fmax(me_exp2, fabs(gexp2(x)-exp2f(x))/(exp2f(x)+1e-9)); }
    printf("exp  rel err: %.2e\n", me_exp);
    printf("exp2 rel err: %.2e (used for pitch ratio 2^(st/12))\n", me_exp2);
    printf("sin  abs err: %.2e\n", me_sin);
    printf("tanh abs err: %.2e\n", me_tanh);
    // determinism sanity: same input -> identical bits twice
    printf("determinism: %s\n", (gexp(1.2345f)==gexp(1.2345f) && gsin(2.1f)==gsin(2.1f))?"exact":"FAIL");
    return 0;
}
