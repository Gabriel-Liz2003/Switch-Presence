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
// -1 means no retry is currently warranted. A successful publish resets it.
int64_t publishRetryDelayMs = -1;

// ClientResult::Error() may be empty even on a failure. Include the type and
// numeric code so Android users can report the actual SDK failure category.
const char* errorCategory(discordpp::ErrorType type) {
    switch (type) {
        case discordpp::ErrorType::None: return "None";
        case discordpp::ErrorType::NetworkError: return "NetworkError";
        case discordpp::ErrorType::HTTPError: return "HTTPError";
        case discordpp::ErrorType::ClientNotReady: return "ClientNotReady";
        case discordpp::ErrorType::Disabled: return "Disabled";
        case discordpp::ErrorType::ClientDestroyed: return "ClientDestroyed";
        case discordpp::ErrorType::ValidationError: return "ValidationError";
        case discordpp::ErrorType::Aborted: return "Aborted";
        case discordpp::ErrorType::AuthorizationFailed: return "AuthorizationFailed";
        case discordpp::ErrorType::RPCError: return "RPCError";
        default: return "Other";
    }
}

std::string failureDetails(discordpp::ClientResult const& result) {
    std::string details = std::string(errorCategory(result.Type())) +
                          " (code " + std::to_string(result.ErrorCode()) + ")";
    std::string description = result.Error();
    if (description.empty()) description = result.ToString();
    if (!description.empty()) details += ": " + description.substr(0, 100);
    return details;
}

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
        publishRetryDelayMs = -1;
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
    publishRetryDelayMs = -1;
    client->UpdateRichPresence(activity, [revision](discordpp::ClientResult result) {
        std::lock_guard<std::recursive_mutex> lock(guard);
        if (revision != presenceRevision) return;
        if (result.Successful()) {
            status = "Atividade publicada";
            publishRetryDelayMs = -1;
        } else {
            status = "Discord recusou: " + failureDetails(result);
            // Honor SDK retry guidance. RPCError with code 0 was also observed
            // to recover on a later publication on the user's Android device.
            const bool canRetry = result.Retryable() ||
                (result.Type() == discordpp::ErrorType::RPCError && result.ErrorCode() == 0);
            const float retryAfterSeconds = result.RetryAfter();
            const int64_t suggestedDelayMs = retryAfterSeconds > 0
                ? static_cast<int64_t>(retryAfterSeconds * 1000.0f) : 15000;
            publishRetryDelayMs = canRetry
                ? (suggestedDelayMs < 15000 ? 15000 : suggestedDelayMs)
                : -1;
        }
    });
}

extern "C" JNIEXPORT void JNICALL
Java_dev_gabriel_switchpresence_DiscordBridge_nativeClear(JNIEnv*, jobject) {
    std::lock_guard<std::recursive_mutex> lock(guard);
    ++presenceRevision;
    publishRetryDelayMs = -1;
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

// Suggested minimum interval before retrying a failed publication. -1 means
// no retry (success, pending result, non-retryable error, or offline clear).
extern "C" JNIEXPORT jlong JNICALL
Java_dev_gabriel_switchpresence_DiscordBridge_nativeRetryDelayMs(JNIEnv*, jobject) {
    std::lock_guard<std::recursive_mutex> lock(guard);
    return static_cast<jlong>(publishRetryDelayMs);
}
