#pragma once

#include <cstdint>

namespace actiongrid::abi {

enum class LauncherAbiProfile : uint8_t {
    kUnknown,
    kLegacy7653,
    kLegacy7722,
    kLegacy7748,
    kCurrent8565,
    kCurrent8584,
};

struct ElfFingerprint {
    uint64_t first = 0;
    uint64_t second = 0;
    bool valid = false;
};

struct KnownFingerprint {
    uint64_t first;
    uint64_t second;
};

// PT_NOTE/.comment are excluded. Build IDs and Dart-only updates are not ABI gates.
constexpr KnownFingerprint kSystemPrivateFingerprint{
        0x5b7a19c56d7704c7ULL, 0xf5c729612d74d0deULL};
constexpr KnownFingerprint kLegacyAidlFingerprint{
        0xd884584d8d3d6fb8ULL, 0xcd7b61ccbcf2dab1ULL};
constexpr KnownFingerprint kCurrentAidlFingerprint{
        0xdaf46dda4c2b40d4ULL, 0xceb378cc00132dc9ULL};
constexpr KnownFingerprint kLegacyUiFingerprint{
        0x06ad928995604d98ULL, 0xe150a44005597071ULL};
constexpr KnownFingerprint kCurrentUiFingerprint{
        0x0447f0269df2c72fULL, 0x98e52e43a1f1887aULL};
constexpr KnownFingerprint kPublicFingerprint{
        0xe3cc5ece19a86f19ULL, 0x68095ade224bb05bULL};
constexpr KnownFingerprint kLegacy7653LauncherFingerprint{
        0x0676d70c1d6227d6ULL, 0xe899ab4db826315eULL};
constexpr KnownFingerprint kLegacy7722LauncherFingerprint{
        0x6aafec65d595b957ULL, 0xd78ecbfd33f5b7afULL};
constexpr KnownFingerprint kLegacy7748LauncherFingerprint{
        0x6018f13c7a9d6274ULL, 0xd0b4054c1d248904ULL};
constexpr KnownFingerprint kLegacy7748FlutterFingerprint{
        0xbf47eb419843a27cULL, 0x13d458eb64bb5168ULL};
constexpr KnownFingerprint kCurrent8565LauncherFingerprint{
        0xd43e8de27a633a7eULL, 0x770a572f4b6ddfc6ULL};
constexpr KnownFingerprint kCurrent8584LauncherFingerprint{
        0x903dbb518fbc909bULL, 0xd492dab28e9324a7ULL};

constexpr bool fingerprintMatches(const ElfFingerprint &actual, KnownFingerprint expected) {
    return actual.valid && actual.first == expected.first && actual.second == expected.second;
}

constexpr LauncherAbiProfile launcherAbiProfileForFingerprint(const ElfFingerprint &launcher) {
    if (fingerprintMatches(launcher, kLegacy7653LauncherFingerprint)) {
        return LauncherAbiProfile::kLegacy7653;
    }
    if (fingerprintMatches(launcher, kLegacy7722LauncherFingerprint)) {
        return LauncherAbiProfile::kLegacy7722;
    }
    if (fingerprintMatches(launcher, kLegacy7748LauncherFingerprint)) {
        return LauncherAbiProfile::kLegacy7748;
    }
    if (fingerprintMatches(launcher, kCurrent8565LauncherFingerprint)) {
        return LauncherAbiProfile::kCurrent8565;
    }
    if (fingerprintMatches(launcher, kCurrent8584LauncherFingerprint)) {
        return LauncherAbiProfile::kCurrent8584;
    }
    return LauncherAbiProfile::kUnknown;
}

constexpr bool isLegacyLauncherProfile(LauncherAbiProfile profile) {
    return profile == LauncherAbiProfile::kLegacy7653
            || profile == LauncherAbiProfile::kLegacy7722
            || profile == LauncherAbiProfile::kLegacy7748;
}

constexpr bool knownSystemAbi(const ElfFingerprint &privateFingerprint,
        const ElfFingerprint &aidl, const ElfFingerprint &ui) {
    return fingerprintMatches(privateFingerprint, kSystemPrivateFingerprint)
            && ((fingerprintMatches(aidl, kLegacyAidlFingerprint)
                    && fingerprintMatches(ui, kLegacyUiFingerprint))
                || (fingerprintMatches(aidl, kCurrentAidlFingerprint)
                    && fingerprintMatches(ui, kCurrentUiFingerprint)));
}

constexpr bool launcherMatchesSystemAbi(LauncherAbiProfile profile,
        const ElfFingerprint &privateFingerprint,
        const ElfFingerprint &aidl, const ElfFingerprint &ui) {
    if (!knownSystemAbi(privateFingerprint, aidl, ui)) return false;
    if (profile == LauncherAbiProfile::kCurrent8565) {
        return fingerprintMatches(aidl, kCurrentAidlFingerprint);
    }
    if (profile == LauncherAbiProfile::kCurrent8584) {
        // The supplied 8584 launcher is shipped on devices whose loaded
        // accessibility pair is still the legacy HyperOS 4 AIDL/UI build.
        // Its private ABI and public Rust symbols are the verified pair above,
        // so accept that exact legacy pair as well as the current one.
        return fingerprintMatches(aidl, kCurrentAidlFingerprint)
                || (fingerprintMatches(aidl, kLegacyAidlFingerprint)
                    && fingerprintMatches(ui, kLegacyUiFingerprint));
    }
    return isLegacyLauncherProfile(profile) && fingerprintMatches(aidl, kLegacyAidlFingerprint);
}

constexpr uint32_t batchMoveOperation(LauncherAbiProfile profile) {
    if (profile == LauncherAbiProfile::kLegacy7748) return 855U;
    if (profile == LauncherAbiProfile::kCurrent8584) return 858U;
    if (profile == LauncherAbiProfile::kLegacy7653
            || profile == LauncherAbiProfile::kLegacy7722) return 854U;
    return 0U;
}

constexpr uint32_t singleMoveOperation(LauncherAbiProfile profile) {
    if (profile == LauncherAbiProfile::kCurrent8565) return 833U;
    if (profile == LauncherAbiProfile::kCurrent8584) return 834U;
    return 0U;
}

constexpr uint32_t insertItemOperation(LauncherAbiProfile profile) {
    if (profile == LauncherAbiProfile::kLegacy7748) return 827U;
    if (profile == LauncherAbiProfile::kCurrent8584) return 830U;
    return 0U;
}

constexpr uint32_t deleteItemOperation(LauncherAbiProfile profile) {
    if (profile == LauncherAbiProfile::kLegacy7748) return 818U;
    if (profile == LauncherAbiProfile::kCurrent8584) return 821U;
    return 0U;
}

constexpr bool supportsFolderTransactions(LauncherAbiProfile profile) {
    return profile == LauncherAbiProfile::kLegacy7748
            || profile == LauncherAbiProfile::kCurrent8584;
}

constexpr bool workspaceContainer(int64_t container) {
    return container == -100 || container == -101;
}

} // namespace actiongrid::abi
