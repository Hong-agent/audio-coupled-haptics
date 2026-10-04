// 原生 Vorbis 编码器（规格书 §6）。
//
// 关键点：3 声道输出时必须写入一条「完整独立」的 comment "ANDROID_HAPTIC=1"，
// 平台 HAL 才会把第 3 声道分离出来驱动振动马达。用键值拼接 API 会粘连字符串导致匹配失败。

#include <jni.h>

#include <android/log.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <ogg/ogg.h>
#include <vorbis/vorbisenc.h>

#define LOG_TAG "hapticogg"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define FEED_CHUNK 1024

namespace {

pthread_mutex_t g_encode_lock = PTHREAD_MUTEX_INITIALIZER;

int write_page(FILE *f, ogg_page *page) {
    if (page->header_len > 0 &&
        fwrite(page->header, 1, (size_t) page->header_len, f) != (size_t) page->header_len) {
        return -1;
    }
    if (page->body_len > 0 &&
        fwrite(page->body, 1, (size_t) page->body_len, f) != (size_t) page->body_len) {
        return -1;
    }
    return 0;
}

/** 把一个 vorbis_block 里所有就绪的包推进 ogg 流并落盘。 */
int drain_block(vorbis_dsp_state *vd, vorbis_block *vb, ogg_stream_state *os, FILE *f) {
    ogg_packet op;
    ogg_page page;
    vorbis_analysis(vb, NULL);
    vorbis_bitrate_addblock(vb);
    while (vorbis_bitrate_flushpacket(vd, &op)) {
        ogg_stream_packetin(os, &op);
        while (ogg_stream_pageout(os, &page)) {
            if (write_page(f, &page) != 0) {
                return -1;
            }
        }
    }
    return 0;
}

int encode_to_file(const char *path, const short *pcm, int frames, int channels,
                   int sample_rate, float quality) {
    FILE *f = fopen(path, "wb");
    if (f == NULL) {
        LOGE("cannot open output %s", path);
        return -1;
    }

    vorbis_info vi;
    vorbis_comment vc;
    vorbis_dsp_state vd;
    vorbis_block vb;
    ogg_stream_state os;
    memset(&vi, 0, sizeof(vi));
    memset(&vc, 0, sizeof(vc));
    memset(&vd, 0, sizeof(vd));
    memset(&vb, 0, sizeof(vb));
    memset(&os, 0, sizeof(os));

    int rc = 0;
    vorbis_info_init(&vi);
    if (vorbis_encode_init_vbr(&vi, channels, sample_rate, quality) != 0) {
        LOGE("vorbis_encode_init_vbr failed");
        vorbis_info_clear(&vi);
        fclose(f);
        return -2;
    }

    vorbis_comment_init(&vc);
    vorbis_comment_add(&vc, "ENCODER=hapticogg");
    if (channels == 3) {
        // ★★ 必须是一条完整的独立 comment，不能写成 ANDROID_HAPTIC + "=1" 拼接
        vorbis_comment_add(&vc, "ANDROID_HAPTIC=1");
    }

    vorbis_analysis_init(&vd, &vi);
    vorbis_block_init(&vd, &vb);
    ogg_stream_init(&os, rand() & 0x7fffffff);

    do {
        ogg_packet h0, h1, h2;
        ogg_page page;
        vorbis_analysis_headerout(&vd, &vc, &h0, &h1, &h2);
        ogg_stream_packetin(&os, &h0);
        ogg_stream_packetin(&os, &h1);
        ogg_stream_packetin(&os, &h2);
        // 用 flush 立即刷盘，降低首播延迟
        while (ogg_stream_flush(&os, &page)) {
            if (write_page(f, &page) != 0) {
                rc = -3;
                break;
            }
        }
        if (rc != 0) {
            break;
        }

        int pos = 0;
        while (pos < frames) {
            int chunk = frames - pos;
            if (chunk > FEED_CHUNK) {
                chunk = FEED_CHUNK;
            }
            float **buffer = vorbis_analysis_buffer(&vd, chunk);
            for (int i = 0; i < chunk; i++) {
                for (int c = 0; c < channels; c++) {
                    buffer[c][i] = (float) pcm[(size_t) (pos + i) * channels + c] / 32768.0f;
                }
            }
            vorbis_analysis_wrote(&vd, chunk);
            pos += chunk;
            while (vorbis_analysis_blockout(&vd, &vb) == 1) {
                if (drain_block(&vd, &vb, &os, f) != 0) {
                    rc = -3;
                    break;
                }
            }
            if (rc != 0) {
                break;
            }
        }
        if (rc != 0) {
            break;
        }

        // 结束流并抽出尾部包
        vorbis_analysis_wrote(&vd, 0);
        while (vorbis_analysis_blockout(&vd, &vb) == 1) {
            if (drain_block(&vd, &vb, &os, f) != 0) {
                rc = -3;
                break;
            }
        }
        if (rc != 0) {
            break;
        }
        while (ogg_stream_flush(&os, &page)) {
            if (write_page(f, &page) != 0) {
                rc = -3;
                break;
            }
        }
    } while (false);

    if (rc != 0) {
        LOGE("write failed");
    }

    // 资源释放顺序与创建顺序相反（规格书 §6.5）
    ogg_stream_clear(&os);
    vorbis_block_clear(&vb);
    vorbis_dsp_clear(&vd);
    vorbis_comment_clear(&vc);
    vorbis_info_clear(&vi);
    fclose(f);
    return rc;
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_com_gaolou_boneconduction_nativebridge_HapticOgg_nativeEncode(
        JNIEnv *env, jclass clazz, jstring path, jshortArray pcm, jint frames,
        jint channels, jint sampleRate, jfloat quality) {
    (void) clazz;
    if (path == NULL || pcm == NULL || frames <= 0 || channels < 1 || channels > 8) {
        return -10;
    }
    const char *cpath = env->GetStringUTFChars(path, NULL);
    if (cpath == NULL) {
        return -11;
    }
    jsize len = env->GetArrayLength(pcm);
    if (len < frames * channels) {
        env->ReleaseStringUTFChars(path, cpath);
        return -12;
    }

    jshort *samples = env->GetShortArrayElements(pcm, NULL);
    if (samples == NULL) {
        env->ReleaseStringUTFChars(path, cpath);
        return -13;
    }

    pthread_mutex_lock(&g_encode_lock);
    int rc = encode_to_file(cpath, samples, frames, channels, sampleRate, quality);
    pthread_mutex_unlock(&g_encode_lock);

    env->ReleaseShortArrayElements(pcm, samples, JNI_ABORT);
    env->ReleaseStringUTFChars(path, cpath);
    return rc;
}

JNIEXPORT jstring JNICALL
Java_com_gaolou_boneconduction_nativebridge_HapticOgg_nativeVersion(
        JNIEnv *env, jclass clazz) {
    (void) clazz;
    return env->NewStringUTF("hapticogg (libogg 1.3.5 + libvorbis 1.3.7)");
}

}  // extern "C"
