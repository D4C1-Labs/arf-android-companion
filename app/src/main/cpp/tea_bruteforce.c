/*
 * PSA TEA Brute-Force — Native C for Android JNI
 * Port of psa.c BF1/BF2 from Flipper firmware
 * Called from TeaBruteForce.kt, split across multiple threads for speed.
 */

#include <jni.h>
#include <stdint.h>
#include <stdbool.h>
#include <string.h>

#define TEA_DELTA  0x9E3779B9U
#define TEA_ROUNDS 32

/* BF1 constants fixed key schedule and counter search range */
#define PSA_BF1_CONST_U4   0x0E0F5C41U
#define PSA_BF1_CONST_U5   0x0F5C4123U
#define PSA_BF1_START      0x23000000U
#define PSA_BF1_END        0x24000000U

static const uint32_t PSA_BF1_KEY_SCHEDULE[4] = {
    0x4A434915U, 0xD6743C2BU, 0x1F29D308U, 0xE6B79A64U,
};

/* BF2 constants key schedule XORed with counter, different search range */
#define PSA_BF2_START 0xF3000000U
#define PSA_BF2_END   0xF4000000U

static const uint32_t PSA_BF2_KEY_SCHEDULE[4] = {
    0x4039C240U, 0xEDA92CABU, 0x4306C02AU, 0x02192A04U,
};

/* CRC-16 lookup table (polynomial 0x8005, no reflection) used by BF2 validation */
static const uint16_t crc16_table[256] = {
    0x0000, 0x8005, 0x800F, 0x000A, 0x801B, 0x001E, 0x0014, 0x8011,
    0x8033, 0x0036, 0x003C, 0x8039, 0x0028, 0x802D, 0x8027, 0x0022,
    0x8063, 0x0066, 0x006C, 0x8069, 0x0078, 0x807D, 0x8077, 0x0072,
    0x0050, 0x8055, 0x805F, 0x005A, 0x804B, 0x004E, 0x0044, 0x8041,
    0x80C3, 0x00C6, 0x00CC, 0x80C9, 0x00D8, 0x80DD, 0x80D7, 0x00D2,
    0x00F0, 0x80F5, 0x80FF, 0x00FA, 0x80EB, 0x00EE, 0x00E4, 0x80E1,
    0x00A0, 0x80A5, 0x80AF, 0x00AA, 0x80BB, 0x00BE, 0x00B4, 0x80B1,
    0x8093, 0x0096, 0x009C, 0x8099, 0x0088, 0x808D, 0x8087, 0x0082,
    0x8183, 0x0186, 0x018C, 0x8189, 0x0198, 0x819D, 0x8197, 0x0192,
    0x01B0, 0x81B5, 0x81BF, 0x01BA, 0x81AB, 0x01AE, 0x01A4, 0x81A1,
    0x01E0, 0x81E5, 0x81EF, 0x01EA, 0x81FB, 0x01FE, 0x01F4, 0x81F1,
    0x81D3, 0x01D6, 0x01DC, 0x81D9, 0x01C8, 0x81CD, 0x81C7, 0x01C2,
    0x0140, 0x8145, 0x814F, 0x014A, 0x815B, 0x015E, 0x0154, 0x8151,
    0x8173, 0x0176, 0x017C, 0x8179, 0x0168, 0x816D, 0x8167, 0x0162,
    0x8123, 0x0126, 0x012C, 0x8129, 0x0138, 0x813D, 0x8137, 0x0132,
    0x0110, 0x8115, 0x811F, 0x011A, 0x810B, 0x010E, 0x0104, 0x8101,
    0x8303, 0x0306, 0x030C, 0x8309, 0x0318, 0x831D, 0x8317, 0x0312,
    0x0330, 0x8335, 0x833F, 0x033A, 0x832B, 0x032E, 0x0324, 0x8321,
    0x0360, 0x8365, 0x836F, 0x036A, 0x837B, 0x037E, 0x0374, 0x8371,
    0x8353, 0x0356, 0x035C, 0x8359, 0x0348, 0x834D, 0x8347, 0x0342,
    0x03C0, 0x83C5, 0x83CF, 0x03CA, 0x83DB, 0x03DE, 0x03D4, 0x83D1,
    0x83F3, 0x03F6, 0x03FC, 0x83F9, 0x03E8, 0x83ED, 0x83E7, 0x03E2,
    0x83A3, 0x03A6, 0x03AC, 0x83A9, 0x03B8, 0x83BD, 0x83B7, 0x03B2,
    0x0390, 0x8395, 0x839F, 0x039A, 0x838B, 0x038E, 0x0384, 0x8381,
    0x0280, 0x8285, 0x828F, 0x028A, 0x829B, 0x029E, 0x0294, 0x8291,
    0x82B3, 0x02B6, 0x02BC, 0x82B9, 0x02A8, 0x82AD, 0x82A7, 0x02A2,
    0x82E3, 0x02E6, 0x02EC, 0x82E9, 0x02F8, 0x82FD, 0x82F7, 0x02F2,
    0x02D0, 0x82D5, 0x82DF, 0x02DA, 0x82CB, 0x02CE, 0x02C4, 0x82C1,
    0x8243, 0x0246, 0x024C, 0x8249, 0x0258, 0x825D, 0x8257, 0x0252,
    0x0270, 0x8275, 0x827F, 0x027A, 0x826B, 0x026E, 0x0264, 0x8261,
    0x0220, 0x8225, 0x822F, 0x022A, 0x823B, 0x023E, 0x0234, 0x8231,
    0x8213, 0x0216, 0x021C, 0x8219, 0x0208, 0x820D, 0x8207, 0x0202,
};

/* Standard TEA encrypt: 32 rounds of Feistel using DELTA schedule. */
static inline void tea_encrypt(uint32_t* v0, uint32_t* v1, const uint32_t* key) {
    uint32_t a = *v0, b = *v1;
    uint32_t sum = 0;
    for (int i = 0; i < TEA_ROUNDS; i++) {
        uint32_t temp = key[sum & 3] + sum;
        a += (temp ^ (((b >> 5) ^ (b << 4)) + b));
        sum += TEA_DELTA;
        temp = key[(sum >> 11) & 3] + sum;
        b += (temp ^ (((a >> 5) ^ (a << 4)) + a));
    }
    *v0 = a;
    *v1 = b;
}

/* Standard TEA decrypt: reverse of tea_encrypt, sum starts at DELTA*ROUNDS. */
static inline void tea_decrypt(uint32_t* v0, uint32_t* v1, const uint32_t* key) {
    uint32_t a = *v0, b = *v1;
    uint32_t sum = TEA_DELTA * TEA_ROUNDS;
    for (int i = 0; i < TEA_ROUNDS; i++) {
        uint32_t temp = key[(sum >> 11) & 3] + sum;
        sum -= TEA_DELTA;
        b -= (temp ^ (((a >> 5) ^ (a << 4)) + a));
        temp = key[sum & 3] + sum;
        a -= (temp ^ (((b >> 5) ^ (b << 4)) + b));
    }
    *v0 = a;
    *v1 = b;
}

/* BF1 checksum: sum of all 7 bytes of the decrypted payload, low byte must match dec_v1[7]. */
static inline uint8_t tea_crc(uint32_t v0, uint32_t v1) {
    uint32_t crc = ((v0 >> 24) & 0xFF) + ((v0 >> 16) & 0xFF) +
                   ((v0 >> 8) & 0xFF) + (v0 & 0xFF);
    crc += ((v1 >> 24) & 0xFF) + ((v1 >> 16) & 0xFF) + ((v1 >> 8) & 0xFF);
    return (uint8_t)(crc & 0xFF);
}

/* CRC-16 using precomputed table used by BF2 to validate decrypted payload. */
static inline uint16_t crc16(const uint8_t* buf, int len) {
    uint16_t crc = 0;
    for (int i = 0; i < len; i++) {
        crc = (crc << 8) ^ crc16_table[((crc >> 8) ^ buf[i]) & 0xFF];
    }
    return crc;
}

/* BfContext: shared state passed into each brute-force range worker.
 * cancel: pointer to a flag set by Kotlin to abort early.
 * keys_tested_arr: JNI int[1] updated with progress count every 64K iterations.
 * Result struct packed into a long:
 *   bits 63:    success (1) or not (0)
 *   bits 31..0: found counter value
 *
 * dec_v0/dec_v1 are written to the output array if success.
 */

typedef struct {
    volatile int* cancel;
    JNIEnv* env;
    jintArray keys_tested_arr;
} BfContext;

/* Write current progress count back to the Kotlin-visible keys_tested array. */
static inline void bf_report_progress(BfContext* ctx, uint32_t count) {
    jint val = (jint)count;
    (*(ctx->env))->SetIntArrayRegion(ctx->env, ctx->keys_tested_arr, 0, 1, &val);
}

/* BF1 worker: derive a working key from counter using the BF1 key schedule,
 * decrypt (w0,w1), validate counter embedded in plaintext and CRC byte. */
static bool bf1_range(uint32_t w0, uint32_t w1,
                      uint32_t start, uint32_t end,
                      BfContext* ctx,
                      uint32_t* out_counter, uint32_t* out_v0, uint32_t* out_v1) {
    for (uint32_t counter = start; counter < end; counter++) {
        if (ctx->cancel && *(ctx->cancel)) return false;

        uint32_t wk2 = PSA_BF1_CONST_U4;
        uint32_t wk3 = counter;
        tea_encrypt(&wk2, &wk3, PSA_BF1_KEY_SCHEDULE);

        uint32_t wk0 = (counter << 8) | 0x0E;
        uint32_t wk1 = PSA_BF1_CONST_U5;
        tea_encrypt(&wk0, &wk1, PSA_BF1_KEY_SCHEDULE);

        uint32_t working_key[4] = {wk0, wk1, wk2, wk3};

        uint32_t dec_v0 = w0;
        uint32_t dec_v1 = w1;
        tea_decrypt(&dec_v0, &dec_v1, working_key);

        if ((counter & 0xFFFFFF) == (dec_v0 >> 8)) {
            uint8_t c = tea_crc(dec_v0, dec_v1);
            if (c == (dec_v1 & 0xFF)) {
                *out_counter = counter;
                *out_v0 = dec_v0;
                *out_v1 = dec_v1;
                return true;
            }
        }

        /* Update progress every 64K iterations */
        if ((counter & 0xFFFF) == 0) {
            bf_report_progress(ctx, counter - start);
        }
    }
    bf_report_progress(ctx, end - start);
    return false;
}

/* BF2 worker: XOR counter into BF2 key schedule to get working key,
 * decrypt (w0,w1), validate counter and CRC-16 over 6 payload bytes. */
static bool bf2_range(uint32_t w0, uint32_t w1,
                      uint32_t start, uint32_t end,
                      BfContext* ctx,
                      uint32_t* out_counter, uint32_t* out_v0, uint32_t* out_v1) {
    for (uint32_t counter = start; counter < end; counter++) {
        if (ctx->cancel && *(ctx->cancel)) return false;

        uint32_t working_key[4] = {
            PSA_BF2_KEY_SCHEDULE[0] ^ counter,
            PSA_BF2_KEY_SCHEDULE[1] ^ counter,
            PSA_BF2_KEY_SCHEDULE[2] ^ counter,
            PSA_BF2_KEY_SCHEDULE[3] ^ counter,
        };

        uint32_t dec_v0 = w0;
        uint32_t dec_v1 = w1;
        tea_decrypt(&dec_v0, &dec_v1, working_key);

        if ((counter & 0xFFFFFF) == (dec_v0 >> 8)) {
            uint8_t crc_buf[6] = {
                (uint8_t)((dec_v0 >> 24) & 0xFF),
                (uint8_t)((dec_v0 >> 8) & 0xFF),
                (uint8_t)((dec_v0 >> 16) & 0xFF),
                (uint8_t)(dec_v0 & 0xFF),
                (uint8_t)((dec_v1 >> 24) & 0xFF),
                (uint8_t)((dec_v1 >> 16) & 0xFF),
            };
            uint16_t c16 = crc16(crc_buf, 6);
            uint16_t expected = (((dec_v1 >> 16) & 0xFF) << 8) | (dec_v1 & 0xFF);

            if (c16 == expected) {
                *out_counter = counter;
                *out_v0 = dec_v0;
                *out_v1 = dec_v1;
                return true;
            }
        }

        if ((counter & 0xFFFF) == 0) {
            bf_report_progress(ctx, counter - start);
        }
    }
    bf_report_progress(ctx, end - start);
    return false;
}

/*
 * JNI entry point: run BF on a sub-range (called per thread)
 *
 * bf_type: 1 = BF1, 2 = BF2
 * w0, w1: encrypted words
 * range_start, range_end: counter range for this thread
 * cancelFlag: int[1] array — set element 0 to non-zero to cancel
 * keysTested: int[1] array — updated with progress count
 * resultOut: int[3] array — [counter, dec_v0, dec_v1] on success
 *
 * Returns: true if found
 */
JNIEXPORT jboolean JNICALL
Java_com_flipper_psadecrypt_TeaBruteForce_nativeBruteForce(
    JNIEnv* env, jobject thiz,
    jint bf_type, jint w0, jint w1,
    jint range_start, jint range_end,
    jintArray cancelFlag, jintArray keysTested, jintArray resultOut) {

    jint* cancel_ptr = (*env)->GetIntArrayElements(env, cancelFlag, NULL);

    BfContext ctx;
    ctx.cancel = (volatile int*)cancel_ptr;
    ctx.env = env;
    ctx.keys_tested_arr = keysTested;

    uint32_t out_counter = 0, out_v0 = 0, out_v1 = 0;
    bool found = false;

    if (bf_type == 1) {
        found = bf1_range((uint32_t)w0, (uint32_t)w1,
                          (uint32_t)range_start, (uint32_t)range_end,
                          &ctx, &out_counter, &out_v0, &out_v1);
    } else {
        found = bf2_range((uint32_t)w0, (uint32_t)w1,
                          (uint32_t)range_start, (uint32_t)range_end,
                          &ctx, &out_counter, &out_v0, &out_v1);
    }

    (*env)->ReleaseIntArrayElements(env, cancelFlag, cancel_ptr, 0);

    if (found) {
        jint result[3] = {(jint)out_counter, (jint)out_v0, (jint)out_v1};
        (*env)->SetIntArrayRegion(env, resultOut, 0, 3, result);
    }

    return (jboolean)found;
}
