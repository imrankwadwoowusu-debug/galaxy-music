// macOS Now Playing Info + remote commands for the desktop player.
//
// The macOS counterpart of Windows SMTC / Linux MPRIS: Control Center, Lock
// Screen, media keys, and Now Playing widget. MediaPlayer.framework is ObjC,
// so this lives beside the Automix analyser as a small JNI library.

#import <AppKit/AppKit.h>
#import <Foundation/Foundation.h>
#import <MediaPlayer/MediaPlayer.h>
#import <jni.h>

#include <atomic>
#include <mutex>
#include <string>

namespace {

JavaVM* g_vm = nullptr;
std::atomic<bool> g_started{false};
std::mutex g_mutex;

constexpr jint BUTTON_PLAY = 0;
constexpr jint BUTTON_PAUSE = 1;
constexpr jint BUTTON_NEXT = 2;
constexpr jint BUTTON_PREVIOUS = 3;
constexpr jint BUTTON_STOP = 4;
constexpr jint BUTTON_TOGGLE = 5;

std::string jstringToUtf8(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string out(chars);
    env->ReleaseStringUTFChars(value, chars);
    return out;
}

void notifyButton(jint button) {
    if (g_vm == nullptr) return;
    JNIEnv* env = nullptr;
    bool attached = false;
    if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_vm->AttachCurrentThreadAsDaemon(reinterpret_cast<void**>(&env), nullptr) != JNI_OK) {
            return;
        }
        attached = true;
    }
    jclass type = env->FindClass("com/music/bitchord/desktop/DesktopMacMedia");
    if (type != nullptr) {
        jmethodID method = env->GetStaticMethodID(type, "onButton", "(I)V");
        if (method != nullptr) {
            env->CallStaticVoidMethod(type, method, button);
        }
        env->DeleteLocalRef(type);
    }
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (attached) g_vm->DetachCurrentThread();
}

void notifySeek(jlong positionMs) {
    if (g_vm == nullptr) return;
    JNIEnv* env = nullptr;
    bool attached = false;
    if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_vm->AttachCurrentThreadAsDaemon(reinterpret_cast<void**>(&env), nullptr) != JNI_OK) {
            return;
        }
        attached = true;
    }
    jclass type = env->FindClass("com/music/bitchord/desktop/DesktopMacMedia");
    if (type != nullptr) {
        jmethodID method = env->GetStaticMethodID(type, "onSeek", "(J)V");
        if (method != nullptr) {
            env->CallStaticVoidMethod(type, method, positionMs);
        }
        env->DeleteLocalRef(type);
    }
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (attached) g_vm->DetachCurrentThread();
}

void installCommands() {
    MPRemoteCommandCenter* center = [MPRemoteCommandCenter sharedCommandCenter];
    center.playCommand.enabled = YES;
    center.pauseCommand.enabled = YES;
    center.togglePlayPauseCommand.enabled = YES;
    center.nextTrackCommand.enabled = YES;
    center.previousTrackCommand.enabled = YES;
    center.stopCommand.enabled = YES;
    center.changePlaybackPositionCommand.enabled = YES;

    [center.playCommand addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent*) {
        notifyButton(BUTTON_PLAY);
        return MPRemoteCommandHandlerStatusSuccess;
    }];
    [center.pauseCommand addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent*) {
        notifyButton(BUTTON_PAUSE);
        return MPRemoteCommandHandlerStatusSuccess;
    }];
    [center.togglePlayPauseCommand addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent*) {
        notifyButton(BUTTON_TOGGLE);
        return MPRemoteCommandHandlerStatusSuccess;
    }];
    [center.nextTrackCommand addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent*) {
        notifyButton(BUTTON_NEXT);
        return MPRemoteCommandHandlerStatusSuccess;
    }];
    [center.previousTrackCommand addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent*) {
        notifyButton(BUTTON_PREVIOUS);
        return MPRemoteCommandHandlerStatusSuccess;
    }];
    [center.stopCommand addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent*) {
        notifyButton(BUTTON_STOP);
        return MPRemoteCommandHandlerStatusSuccess;
    }];
    [center.changePlaybackPositionCommand
        addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent* event) {
            auto* seek = (MPChangePlaybackPositionCommandEvent*)event;
            notifySeek(static_cast<jlong>(seek.positionTime * 1000.0));
            return MPRemoteCommandHandlerStatusSuccess;
        }];
}

void clearCommands() {
    MPRemoteCommandCenter* center = [MPRemoteCommandCenter sharedCommandCenter];
    [center.playCommand removeTarget:nil];
    [center.pauseCommand removeTarget:nil];
    [center.togglePlayPauseCommand removeTarget:nil];
    [center.nextTrackCommand removeTarget:nil];
    [center.previousTrackCommand removeTarget:nil];
    [center.stopCommand removeTarget:nil];
    [center.changePlaybackPositionCommand removeTarget:nil];
    center.playCommand.enabled = NO;
    center.pauseCommand.enabled = NO;
    center.togglePlayPauseCommand.enabled = NO;
    center.nextTrackCommand.enabled = NO;
    center.previousTrackCommand.enabled = NO;
    center.stopCommand.enabled = NO;
    center.changePlaybackPositionCommand.enabled = NO;
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT jboolean JNICALL
Java_com_music_bitchord_desktop_DesktopMacMedia_nativeStart(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> guard(g_mutex);
    if (g_started.exchange(true)) return JNI_TRUE;
    __block bool ok = false;
    if ([NSThread isMainThread]) {
        installCommands();
        ok = true;
    } else {
        dispatch_sync(dispatch_get_main_queue(), ^{
            installCommands();
            ok = true;
        });
    }
    if (!ok) {
        g_started = false;
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_desktop_DesktopMacMedia_nativeStop(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> guard(g_mutex);
    if (!g_started.exchange(false)) return;
    auto clear = ^{
        clearCommands();
        [MPNowPlayingInfoCenter defaultCenter].nowPlayingInfo = nil;
        [MPNowPlayingInfoCenter defaultCenter].playbackState = MPNowPlayingPlaybackStateStopped;
    };
    if ([NSThread isMainThread]) {
        clear();
    } else {
        dispatch_sync(dispatch_get_main_queue(), clear);
    }
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_desktop_DesktopMacMedia_nativeUpdate(
    JNIEnv* env, jobject,
    jstring title, jstring artist, jstring album, jstring artUrl,
    jboolean playing, jlong positionMs, jlong durationMs) {
    if (!g_started.load()) return;

    NSString* nsTitle = [NSString stringWithUTF8String:jstringToUtf8(env, title).c_str()];
    NSString* nsArtist = [NSString stringWithUTF8String:jstringToUtf8(env, artist).c_str()];
    NSString* nsAlbum = [NSString stringWithUTF8String:jstringToUtf8(env, album).c_str()];
    std::string art = jstringToUtf8(env, artUrl);
    bool isPlaying = playing == JNI_TRUE;
    double positionSec = positionMs / 1000.0;
    double durationSec = durationMs / 1000.0;

    auto apply = ^{
        NSMutableDictionary* info = [NSMutableDictionary dictionary];
        if (nsTitle.length > 0) info[MPMediaItemPropertyTitle] = nsTitle;
        if (nsArtist.length > 0) info[MPMediaItemPropertyArtist] = nsArtist;
        if (nsAlbum.length > 0) info[MPMediaItemPropertyAlbumTitle] = nsAlbum;
        if (durationSec > 0) {
            info[MPMediaItemPropertyPlaybackDuration] = @(durationSec);
        }
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = @(positionSec);
        info[MPNowPlayingInfoPropertyPlaybackRate] = isPlaying ? @1.0 : @0.0;
        info[MPNowPlayingInfoPropertyDefaultPlaybackRate] = @1.0;

        if (!art.empty()) {
            NSURL* url = [NSURL URLWithString:[NSString stringWithUTF8String:art.c_str()]];
            if (url != nil) {
                // Best-effort artwork; a failed fetch must not block metadata.
                dispatch_async(dispatch_get_global_queue(QOS_CLASS_UTILITY, 0), ^{
                    NSData* data = [NSData dataWithContentsOfURL:url];
                    if (data == nil) return;
                    NSImage* image = [[NSImage alloc] initWithData:data];
                    if (image == nil) return;
                    MPMediaItemArtwork* artwork =
                        [[MPMediaItemArtwork alloc] initWithBoundsSize:image.size
                                                        requestHandler:^NSImage*(CGSize) {
                                                            return image;
                                                        }];
                    dispatch_async(dispatch_get_main_queue(), ^{
                        NSMutableDictionary* current =
                            [[MPNowPlayingInfoCenter defaultCenter].nowPlayingInfo mutableCopy]
                                ?: [NSMutableDictionary dictionary];
                        current[MPMediaItemPropertyArtwork] = artwork;
                        [MPNowPlayingInfoCenter defaultCenter].nowPlayingInfo = current;
                    });
                });
            }
        }

        [MPNowPlayingInfoCenter defaultCenter].nowPlayingInfo = info;
        [MPNowPlayingInfoCenter defaultCenter].playbackState =
            isPlaying ? MPNowPlayingPlaybackStatePlaying : MPNowPlayingPlaybackStatePaused;

        // Become the Now Playing target for media keys.
        [[NSApplication sharedApplication] setActivationPolicy:NSApplicationActivationPolicyRegular];
    };

    if ([NSThread isMainThread]) {
        apply();
    } else {
        dispatch_async(dispatch_get_main_queue(), apply);
    }
}

}  // extern "C"
