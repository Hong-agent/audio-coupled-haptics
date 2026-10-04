/*
 * 主机侧编码自检：用与 JNI 完全相同的调用序列生成一段 3 声道 Ogg/Vorbis，
 * 再交给 tools/verify_ogg_comment.py 校验 ANDROID_HAPTIC=1 标记。
 *
 * 这是规格书 §11.1「Comment 验证」的落地版本，不需要手机即可跑。
 */

#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <ogg/ogg.h>
#include <vorbis/vorbisenc.h>

#define FEED_CHUNK 1024

static int write_page(FILE *f, ogg_page *page) {
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

static int drain_block(vorbis_dsp_state *vd, vorbis_block *vb,
                       ogg_stream_state *os, FILE *f) {
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

int main(int argc, char **argv) {
    const char *out_path = argc > 1 ? argv[1] : "host_test.ogg";
    const int channels = 3;
    const int rate = 48000;
    const int frames = 24000;
    const float quality = 0.5f;

    short *pcm = (short *) malloc(sizeof(short) * frames * channels);
    if (pcm == NULL) {
        return 1;
    }
    for (int i = 0; i < frames; i++) {
        double t = (double) i / rate;
        pcm[i * 3] = (short) (12000 * sin(2 * M_PI * 440 * t));
        pcm[i * 3 + 1] = (short) (12000 * sin(2 * M_PI * 660 * t));
        /* 触觉声道：低频力度 + 高频细节，模拟 HapticMixer 的输出形态 */
        pcm[i * 3 + 2] = (short) (9000 * sin(2 * M_PI * 90 * t)
                                  + 4000 * sin(2 * M_PI * 1800 * t));
    }

    FILE *f = fopen(out_path, "wb");
    if (f == NULL) {
        free(pcm);
        return 1;
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

    vorbis_info_init(&vi);
    if (vorbis_encode_init_vbr(&vi, channels, rate, quality) != 0) {
        fprintf(stderr, "vorbis_encode_init_vbr failed\n");
        return 2;
    }
    vorbis_comment_init(&vc);
    vorbis_comment_add(&vc, "ENCODER=hapticogg");
    vorbis_comment_add(&vc, "ANDROID_HAPTIC=1"); /* ★ 必须完整独立一条 */

    vorbis_analysis_init(&vd, &vi);
    vorbis_block_init(&vd, &vb);
    ogg_stream_init(&os, 1234567);

    ogg_packet h0, h1, h2;
    ogg_page page;
    vorbis_analysis_headerout(&vd, &vc, &h0, &h1, &h2);
    ogg_stream_packetin(&os, &h0);
    ogg_stream_packetin(&os, &h1);
    ogg_stream_packetin(&os, &h2);
    while (ogg_stream_flush(&os, &page)) {
        write_page(f, &page);
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
                fprintf(stderr, "drain failed\n");
                return 3;
            }
        }
    }
    vorbis_analysis_wrote(&vd, 0);
    while (vorbis_analysis_blockout(&vd, &vb) == 1) {
        drain_block(&vd, &vb, &os, f);
    }
    while (ogg_stream_flush(&os, &page)) {
        write_page(f, &page);
    }

    ogg_stream_clear(&os);
    vorbis_block_clear(&vb);
    vorbis_dsp_clear(&vd);
    vorbis_comment_clear(&vc);
    vorbis_info_clear(&vi);
    fclose(f);
    free(pcm);
    printf("wrote %s (%d frames, %d ch, %d Hz)\n", out_path, frames, channels, rate);
    return 0;
}
