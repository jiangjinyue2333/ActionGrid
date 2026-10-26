#include "../../main/cpp/native_abi_profiles.h"
#include "../../main/cpp/zip_directory.h"

#include <array>

using namespace actiongrid::abi;

constexpr ElfFingerprint observed(KnownFingerprint value) {
    return {value.first, value.second, true};
}

static_assert(launcherAbiProfileForFingerprint(observed(kLegacy7748LauncherFingerprint))
        == LauncherAbiProfile::kLegacy7748);
static_assert(batchMoveOperation(LauncherAbiProfile::kLegacy7748) == 855U);
static_assert(batchMoveOperation(LauncherAbiProfile::kLegacy7722) == 854U);
static_assert(batchMoveOperation(LauncherAbiProfile::kCurrent8584) == 858U);
static_assert(batchMoveOperation(LauncherAbiProfile::kUnknown) == 0U);
static_assert(batchMoveOperation(LauncherAbiProfile::kCurrent8565) == 0U);
static_assert(singleMoveOperation(LauncherAbiProfile::kCurrent8565) == 833U);
static_assert(singleMoveOperation(LauncherAbiProfile::kCurrent8584) == 834U);
static_assert(singleMoveOperation(LauncherAbiProfile::kUnknown) == 0U);
static_assert(insertItemOperation(LauncherAbiProfile::kCurrent8584) == 830U);
static_assert(deleteItemOperation(LauncherAbiProfile::kCurrent8584) == 821U);
static_assert(supportsFolderTransactions(LauncherAbiProfile::kCurrent8584));
static_assert(!supportsFolderTransactions(LauncherAbiProfile::kCurrent8565));
static_assert(launcherAbiProfileForFingerprint({}) == LauncherAbiProfile::kUnknown);
static_assert(launcherAbiProfileForFingerprint({kLegacy7748LauncherFingerprint.first,
        kLegacy7748LauncherFingerprint.second, false}) == LauncherAbiProfile::kUnknown);
static_assert(launcherAbiProfileForFingerprint({kLegacy7748LauncherFingerprint.first ^ 1,
        kLegacy7748LauncherFingerprint.second, true}) == LauncherAbiProfile::kUnknown);

constexpr auto privateAbi = observed(kSystemPrivateFingerprint);
constexpr auto legacyAidl = observed(kLegacyAidlFingerprint);
constexpr auto legacyUi = observed(kLegacyUiFingerprint);
constexpr auto currentAidl = observed(kCurrentAidlFingerprint);
constexpr auto currentUi = observed(kCurrentUiFingerprint);
// An unknown launcher retains basic actions only when the entire system pair is known.
static_assert(knownSystemAbi(privateAbi, legacyAidl, legacyUi));
static_assert(knownSystemAbi(privateAbi, currentAidl, currentUi));
static_assert(!knownSystemAbi({}, legacyAidl, legacyUi));
static_assert(!knownSystemAbi(privateAbi, legacyAidl, currentUi));
static_assert(!launcherMatchesSystemAbi(LauncherAbiProfile::kUnknown,
        privateAbi, legacyAidl, legacyUi));
static_assert(launcherMatchesSystemAbi(LauncherAbiProfile::kLegacy7748,
        privateAbi, legacyAidl, legacyUi));
static_assert(!launcherMatchesSystemAbi(LauncherAbiProfile::kLegacy7748,
        privateAbi, currentAidl, currentUi));
static_assert(launcherMatchesSystemAbi(LauncherAbiProfile::kCurrent8565,
        privateAbi, currentAidl, currentUi));
static_assert(launcherMatchesSystemAbi(LauncherAbiProfile::kCurrent8584,
        privateAbi, currentAidl, currentUi));
static_assert(launcherMatchesSystemAbi(LauncherAbiProfile::kCurrent8584,
        privateAbi, legacyAidl, legacyUi));

// Folder child moves require a separate transaction that maintains folder membership.
static_assert(workspaceContainer(-100));
static_assert(workspaceContainer(-101));
static_assert(!workspaceContainer(42));
static_assert(!workspaceContainer(0));
static_assert(!workspaceContainer(-102));

constexpr std::array<uint8_t, 22> emptyZip{0x50, 0x4b, 0x05, 0x06};
static_assert(actiongrid::zip::endRecordOffset(emptyZip.data(), emptyZip.size()) == 0);
static_assert(actiongrid::zip::endRecordOffset(emptyZip.data(), 21) == 21);
constexpr std::array<uint8_t, 24> commentedZip = [] {
    std::array<uint8_t, 24> bytes{0x50, 0x4b, 0x05, 0x06};
    bytes[20] = 2;
    return bytes;
}();
static_assert(actiongrid::zip::endRecordOffset(commentedZip.data(), 24) == 0);
static_assert(actiongrid::zip::endRecordOffset(commentedZip.data(), 23) == 23);
constexpr std::array<uint8_t, 32> missingZipFooter{};
static_assert(actiongrid::zip::endRecordOffset(missingZipFooter.data(), 32) == 32);
