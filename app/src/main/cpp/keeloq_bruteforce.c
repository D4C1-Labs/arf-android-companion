#define _GNU_SOURCE
#include <jni.h>
#include <stdint.h>
#include <stdbool.h>
#include <stdio.h>
#include <sched.h>
#include <unistd.h>

#define KLQ_NLF 0x3A5C742EU
#define bit(x, n) (((x) >> (n)) & 1)
#define g5(x, a, b, c, d, e) \
    (bit(x,a) + bit(x,b)*2 + bit(x,c)*4 + bit(x,d)*8 + bit(x,e)*16)

#define MAX_BF_THREADS 16
static volatile int32_t g_keys_tested[MAX_BF_THREADS];
static volatile int32_t g_cancel[MAX_BF_THREADS];

static int g_big_cores[MAX_BF_THREADS];
static int g_num_big_cores = 0;
static int g_cores_detected = 0;

/* Read cpufreq to find the fastest cores (big cores on big.LITTLE).
 * Threads are pinned to these to maximise brute-force throughput. */
#define MAX_CANDIDATES 32
typedef struct {
    uint64_t mfkey;
    uint64_t devkey;
    uint32_t cnt;
    int32_t learn_type;
} KlCandidate;

static volatile int32_t g_num_candidates = 0;
static KlCandidate g_candidates[MAX_CANDIDATES];

static void detect_big_cores(void) {
    if (g_cores_detected) return;
    int ncpus = sysconf(_SC_NPROCESSORS_CONF);
    if (ncpus <= 0 || ncpus > MAX_BF_THREADS) ncpus = MAX_BF_THREADS;

    uint32_t max_freq[MAX_BF_THREADS];
    uint32_t highest = 0;

    for (int i = 0; i < ncpus; i++) {
        char path[128];
        snprintf(path, sizeof(path),
            "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", i);
        FILE* f = fopen(path, "r");
        if (f) {
            fscanf(f, "%u", &max_freq[i]);
            fclose(f);
            if (max_freq[i] > highest) highest = max_freq[i];
        } else {
            max_freq[i] = 0;
        }
    }

    g_num_big_cores = 0;
    uint32_t threshold = highest * 8 / 10;
    for (int i = 0; i < ncpus; i++) {
        if (max_freq[i] >= threshold) {
            g_big_cores[g_num_big_cores++] = i;
        }
    }
    if (g_num_big_cores == 0) {
        for (int i = 0; i < ncpus; i++)
            g_big_cores[g_num_big_cores++] = i;
    }
    g_cores_detected = 1;
}

static void pin_to_big_core(int thread_idx) {
    detect_big_cores();
    int core = g_big_cores[thread_idx % g_num_big_cores];
    cpu_set_t set;
    CPU_ZERO(&set);
    CPU_SET(core, &set);
    sched_setaffinity(0, sizeof(set), &set);
}

static uint32_t keeloq_decrypt(uint32_t data, uint64_t key) {
    uint32_t x = data;
    for (int r = 0; r < 528; r++) {
        x = (x << 1) ^ bit(x, 31) ^ bit(x, 15)
            ^ (uint32_t)bit(key, (15 - r) & 63)
            ^ bit(KLQ_NLF, g5(x, 0, 8, 19, 25, 30));
    }
    return x;
}

static inline bool validate_hop(uint32_t dec, uint8_t expected_btn, uint16_t expected_disc) {
    if ((dec >> 28) != expected_btn) return false;
    uint16_t disc = (dec >> 16) & 0x3FF;
    if (disc == expected_disc) return true;
    if ((disc & 0xFF) == (expected_disc & 0xFF)) return true;
    return false;
}

/* Type 6 (Magic Serial Simple): manufacturer key derived from serial bytes.
 * Upper 40 bits fixed from serial, lower 32 bits are searched. */
static inline void store_candidate(uint64_t mfkey, uint64_t devkey, uint32_t cnt, int32_t learn_type) {
    int32_t idx = __sync_fetch_and_add(&g_num_candidates, 1);
    if (idx < MAX_CANDIDATES) {
        g_candidates[idx].mfkey = mfkey;
        g_candidates[idx].devkey = devkey;
        g_candidates[idx].cnt = cnt;
        g_candidates[idx].learn_type = learn_type;
    }
}

static void brute_type6(
    uint32_t serial, uint32_t hop1, uint32_t hop2,
    uint8_t btn, uint16_t disc,
    uint32_t range_start, uint32_t range_end,
    int thread_idx)
{
    uint64_t upper = ((uint64_t)(serial & 0x00FFFFFF) << 40)
        | ((uint64_t)(((serial & 0xFF) + ((serial >> 8) & 0xFF)) & 0xFF) << 32);

    uint64_t start64 = (uint64_t)(range_start & 0xFFFFFFFFULL);
    uint64_t end64 = (range_end == 0) ? 0x100000000ULL : (uint64_t)(range_end & 0xFFFFFFFFULL);

    for (uint64_t man_lo = start64; man_lo < end64; man_lo++) {
        if (g_cancel[thread_idx]) return;

        uint64_t devkey = upper | (uint32_t)man_lo;
        uint32_t dec1 = keeloq_decrypt(hop1, devkey);

        if (!validate_hop(dec1, btn, disc)) {
            if ((man_lo & 0xFFFF) == 0)
                g_keys_tested[thread_idx] = (int32_t)(man_lo - start64);
            continue;
        }

        uint32_t dec2 = keeloq_decrypt(hop2, devkey);
        if (validate_hop(dec2, btn, disc)) {
            uint16_t cnt1 = dec1 & 0xFFFF;
            uint16_t cnt2 = dec2 & 0xFFFF;
            int diff = (int)cnt2 - (int)cnt1;
            if (diff >= 1 && diff <= 256) {
                store_candidate(upper | (uint32_t)man_lo, devkey, cnt1, 6);
            }
        }
        if ((man_lo & 0xFFFF) == 0)
            g_keys_tested[thread_idx] = (int32_t)(man_lo - start64);
    }
    g_keys_tested[thread_idx] = (int32_t)(end64 - start64);
}

/* Type 7 (Magic Serial Custom): upper 4 bytes taken directly from fix word,
 * lower 32 bits are searched. */
static void brute_type7(
    uint32_t serial, uint32_t fix, uint32_t hop1, uint32_t hop2,
    uint8_t btn, uint16_t disc,
    uint32_t range_start, uint32_t range_end,
    int thread_idx)
{
    uint8_t s0 = fix & 0xFF;
    uint8_t s1 = (fix >> 8) & 0xFF;
    uint8_t s2 = (fix >> 16) & 0xFF;
    uint8_t s3 = (fix >> 24) & 0xFF;

    uint64_t start64 = (uint64_t)(range_start & 0xFFFFFFFFULL);
    uint64_t end64 = (range_end == 0) ? 0x100000000ULL : (uint64_t)(range_end & 0xFFFFFFFFULL);

    for (uint64_t man_lo = start64; man_lo < end64; man_lo++) {
        if (g_cancel[thread_idx]) return;

        uint64_t man = (uint32_t)man_lo;
        uint8_t* m = (uint8_t*)&man;
        m[4] = s3;
        m[5] = s2;
        m[6] = s1;
        m[7] = s0;

        uint64_t devkey = man;
        uint32_t dec1 = keeloq_decrypt(hop1, devkey);

        if (!validate_hop(dec1, btn, disc)) {
            if ((man_lo & 0xFFFF) == 0)
                g_keys_tested[thread_idx] = (int32_t)(man_lo - start64);
            continue;
        }

        uint32_t dec2 = keeloq_decrypt(hop2, devkey);
        if (validate_hop(dec2, btn, disc)) {
            uint16_t cnt1 = dec1 & 0xFFFF;
            uint16_t cnt2 = dec2 & 0xFFFF;
            int diff = (int)cnt2 - (int)cnt1;
            if (diff >= 1 && diff <= 256) {
                store_candidate(man, devkey, cnt1, 7);
            }
        }
        if ((man_lo & 0xFFFF) == 0)
            g_keys_tested[thread_idx] = (int32_t)(man_lo - start64);
    }
    g_keys_tested[thread_idx] = (int32_t)(end64 - start64);
}

/* Type 8 (Magic Serial Extended): lower 24 bits fixed from serial,
 * upper 40 bits are searched. */
static void brute_type8(
    uint32_t serial, uint32_t hop1, uint32_t hop2,
    uint8_t btn, uint16_t disc,
    uint32_t range_start, uint32_t range_end,
    int thread_idx)
{
    uint32_t serial_lo24 = serial & 0xFFFFFF;

    uint64_t start64 = (uint64_t)(range_start & 0xFFFFFFFFULL);
    uint64_t end64 = (range_end == 0) ? 0x100000000ULL : (uint64_t)(range_end & 0xFFFFFFFFULL);

    for (uint64_t upper = start64; upper < end64; upper++) {
        if (g_cancel[thread_idx]) return;

        uint64_t man = (upper << 24) | serial_lo24;
        uint64_t devkey = man;
        uint32_t dec1 = keeloq_decrypt(hop1, devkey);

        if (!validate_hop(dec1, btn, disc)) {
            if ((upper & 0xFFFF) == 0)
                g_keys_tested[thread_idx] = (int32_t)(upper - start64);
            continue;
        }

        uint32_t dec2 = keeloq_decrypt(hop2, devkey);
        if (validate_hop(dec2, btn, disc)) {
            uint16_t cnt1 = dec1 & 0xFFFF;
            uint16_t cnt2 = dec2 & 0xFFFF;
            int diff = (int)cnt2 - (int)cnt1;
            if (diff >= 1 && diff <= 256) {
                store_candidate(man, devkey, cnt1, 8);
            }
        }
        if ((upper & 0xFFFF) == 0)
            g_keys_tested[thread_idx] = (int32_t)(upper - start64);
    }
    g_keys_tested[thread_idx] = (int32_t)(end64 - start64);
}

JNIEXPORT void JNICALL
Java_com_flipper_psadecrypt_KeeloqBruteForce_nativeResetThread(
    JNIEnv* env, jobject thiz, jint thread_idx)
{
    if (thread_idx >= 0 && thread_idx < MAX_BF_THREADS) {
        g_keys_tested[thread_idx] = 0;
        g_cancel[thread_idx] = 0;
    }
}

JNIEXPORT void JNICALL
Java_com_flipper_psadecrypt_KeeloqBruteForce_nativeSetCancel(
    JNIEnv* env, jobject thiz, jint thread_idx)
{
    if (thread_idx >= 0 && thread_idx < MAX_BF_THREADS) {
        g_cancel[thread_idx] = 1;
    }
}

JNIEXPORT jint JNICALL
Java_com_flipper_psadecrypt_KeeloqBruteForce_nativeGetBigCoreCount(
    JNIEnv* env, jobject thiz)
{
    detect_big_cores();
    return (jint)g_num_big_cores;
}

JNIEXPORT jint JNICALL
Java_com_flipper_psadecrypt_KeeloqBruteForce_nativeGetKeysTested(
    JNIEnv* env, jobject thiz, jint thread_idx)
{
    if (thread_idx >= 0 && thread_idx < MAX_BF_THREADS) {
        return (jint)g_keys_tested[thread_idx];
    }
    return 0;
}

JNIEXPORT void JNICALL
Java_com_flipper_psadecrypt_KeeloqBruteForce_nativeResetCandidates(
    JNIEnv* env, jobject thiz)
{
    g_num_candidates = 0;
}

JNIEXPORT jint JNICALL
Java_com_flipper_psadecrypt_KeeloqBruteForce_nativeGetCandidateCount(
    JNIEnv* env, jobject thiz)
{
    return (jint)g_num_candidates;
}

JNIEXPORT jboolean JNICALL
Java_com_flipper_psadecrypt_KeeloqBruteForce_nativeGetCandidate(
    JNIEnv* env, jobject thiz, jint index, jlongArray result_out)
{
    if (index < 0 || index >= g_num_candidates || index >= MAX_CANDIDATES) {
        return JNI_FALSE;
    }
    jlong result[4];
    result[0] = (jlong)g_candidates[index].mfkey;
    result[1] = (jlong)g_candidates[index].devkey;
    result[2] = (jlong)g_candidates[index].cnt;
    result[3] = (jlong)g_candidates[index].learn_type;
    (*env)->SetLongArrayRegion(env, result_out, 0, 4, result);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_flipper_psadecrypt_KeeloqBruteForce_nativeBruteForce(
    JNIEnv* env, jobject thiz,
    jint learning_type,
    jint serial, jint fix,
    jint hop1, jint hop2,
    jint range_start, jint range_end,
    jint thread_idx)
{
    pin_to_big_core(thread_idx);

    uint8_t btn = ((uint32_t)fix) >> 28;
    uint16_t disc = ((uint32_t)serial) & 0x3FF;

    switch (learning_type) {
    case 6:
        brute_type6(
            (uint32_t)serial, (uint32_t)hop1, (uint32_t)hop2,
            btn, disc,
            (uint32_t)range_start, (uint32_t)range_end,
            thread_idx);
        break;
    case 7:
        brute_type7(
            (uint32_t)serial, (uint32_t)fix, (uint32_t)hop1, (uint32_t)hop2,
            btn, disc,
            (uint32_t)range_start, (uint32_t)range_end,
            thread_idx);
        break;
    case 8:
        brute_type8(
            (uint32_t)serial, (uint32_t)hop1, (uint32_t)hop2,
            btn, disc,
            (uint32_t)range_start, (uint32_t)range_end,
            thread_idx);
        break;
    }
}
