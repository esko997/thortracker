#include "chip_opll.h"

#include <pthread.h>

static pthread_mutex_t creation_mutex = PTHREAD_MUTEX_INITIALIZER;

OPLL* chip_opll_new(uint32_t clock, uint32_t rate) {
    pthread_mutex_lock(&creation_mutex);
    OPLL* opll = OPLL_new(clock, rate);
    pthread_mutex_unlock(&creation_mutex);
    return opll;
}
