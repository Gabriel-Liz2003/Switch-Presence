#define DISCORDPP_IMPLEMENTATION
#include "discordpp.h"
#include <jni.h>
#include <memory>
#include <mutex>
#include <string>
#include <cstdint>

namespace {
std::recursive_mutex guard;
std::unique_ptr<discordpp::Client> client;
std::string status = "Discord não conectado";
uint64_t presenceRevision = 0;

std::string utf(JNIEnv* env, jstring value) {
    if (!value) return "";
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) return "";
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_gabriel_switchpresence_DiscordBridge_nativeSetup(JNIEnv* env, jobject, jstring appId) {
    std::lock_guard<std::recursive_mutex> lock(guard);
    try {
        std::string input = utf(env, appId);
        if (input.empty() || input.find_first_not_of("0123456789") != std::string::npos)
            return JNI_FALSE;
        uint64_t id = std::stoull(input);
        if (!id) return JNI_FALSE;
        // Rebuild the RPC transport when Android/Discord has backgrounded it.
        // Invalidate callbacks for the previous Client before releasing it.
        auto replacement = std::make_unique<discordpp::Client>();
        replacement->SetApplicationId(id);
        ++presenceRevision;
        client.swap(replacement);
        status = "Canal do Discord reinicializado";
        return JNI_TRUE;
    } catch (...) {
        status = "ID inválido ou falha no SDK";
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_gabriel_switchpresence_DiscordBridge_nativePublish(
    JNIEnv* env, jobject, jstring titleArg, jstring imageArg, jstring platformArg, jlong start) {
    std::lock_guard<std::recursive_mutex> lock(guard);
    if (!client) return;
    std::string title = utf(env, titleArg);
    if (title.empty()) return;

    discordpp::Activity activity;
    activity.SetType(discordpp::ActivityTypes::Playing);
    activity.SetName(title);
    activity.SetDetails("Jogando no Nintendo Switch");
    activity.SetState(utf(env, platformArg));
    if (start > 0) {
        discordpp::ActivityTimestamps time;
        time.SetStart(static_cast<uint64_t>(start)); // milliseconds since Unix epoch
        activity.SetTimestamps(time);
    }
    std::string image = utf(env, imageArg);
    if (image.rfind("https://", 0) == 0 && image.size() < 500) {
        discordpp::ActivityAssets assets;
        assets.SetLargeImage(image);
        assets.SetLargeText(title.substr(0, 128));
        activity.SetAssets(assets);
    }
    // Ignore late publication callbacks after ClearRichPresence was requested.
    const uint64_t revision = ++presenceRevision;
    status = "Enviando atividade ao Discord";
    client->UpdateRichPresence(activity, [revision](discordpp::ClientResult result) {
        std::lock_guard<std::recursive_mutex> lock(guard);
        if (revision != presenceRevision) return;
        status = result.Successful() ? "Atividade publicada" : "Discord recusou: " + result.Error().substr(0, 180);
    });
}

extern "C" JNIEXPORT void JNICALL
Java_dev_gabriel_switchpresence_DiscordBridge_nativeClear(JNIEnv*, jobject) {
    std::lock_guard<std::recursive_mutex> lock(guard);
    ++presenceRevision;
    if (client) {
        client->ClearRichPresence();
        // ClearRichPresence has no result callback. Do not claim server confirmation.
        status = "Pedido de remoção enviado";
    } else {
        status = "Discord não conectado";
    }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_gabriel_switchpresence_DiscordBridge_nativePump(JNIEnv*, jobject) {
    std::lock_guard<std::recursive_mutex> lock(guard);
    if (client) discordpp::RunCallbacks();
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_gabriel_switchpresence_DiscordBridge_nativeLastStatus(JNIEnv* env, jobject) {
    std::lock_guard<std::recursive_mutex> lock(guard);
    return env->NewStringUTF(status.c_str());
}
