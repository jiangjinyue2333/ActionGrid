// Included by native_grid_test.cpp: exercise the same native logger and ABI bridge.
namespace {
using DiagnosticBuffer = actiongrid::diagnostics::Buffer;
constexpr char DiagnosticsProviderAuthority[] = "io.github.jiangjinyue2333.actiongrid.diagnostics";

void diagnosticsBufferRespectsEpochs() {
    DiagnosticBuffer buffer;
    buffer.append("disabled", 0);
    require(buffer.batch().text.empty(), "logging disabled still buffers text");
    buffer.setSession(11);
    buffer.append("old session", 11);
    auto old = buffer.batch();
    buffer.setSession(0);
    require(buffer.batch().text.empty(), "disable leaves queued records");
    buffer.setSession(12);
    buffer.append("late old producer", 11);
    buffer.append("new session", 12);
    buffer.acknowledge(old);
    require(buffer.batch().text.find("new session") != std::string::npos
                    && buffer.batch().text.find("old") == std::string::npos,
            "late producer or old acknowledgement crossed a logging toggle");
}

void diagnosticsBufferBoundsAndRetries() {
    DiagnosticBuffer buffer;
    buffer.setSession(13);
    for (int i = 0; i < 300; ++i) buffer.append("entry=" + std::to_string(i), 13);
    auto first = buffer.batch();
    require(first.dropped == 44 && first.text.find("dropped=44") != std::string::npos,
            "overflow must report how many records were lost");
    require(first.text.find("entry=0\n") == std::string::npos
                    && first.text.find("entry=44\n") != std::string::npos,
            "bounded queue did not preserve its latest records");
    require(std::count(first.text.begin(), first.text.end(), '\n') <= 64,
            "batch exceeds the provider line limit");
    require(buffer.batch().text == first.text, "failed transport silently consumes records");
    buffer.append("newest", 13);
    buffer.acknowledge(first);
    std::string remaining;
    for (;;) {
        auto batch = buffer.batch();
        if (batch.text.empty()) break;
        require(batch.text.size() <= DiagnosticBuffer::kMaxBatchBytes, "batch is too large for Binder");
        remaining += batch.text;
        buffer.acknowledge(batch);
    }
    require(remaining.find("entry=299\n") != std::string::npos
                    && remaining.find("newest") != std::string::npos,
            "ack consumed entries produced after the batch was captured");
}

void diagnosticsBufferPreservesUtf8AndBounds() {
    DiagnosticBuffer buffer;
    buffer.setSession(14);
    std::string longLine;
    for (int i = 0; i < 2000; ++i) longLine += "桌";
    for (int i = 0; i < 70; ++i) buffer.append(longLine, 14);
    auto batch = buffer.batch();
    require(batch.text.size() <= DiagnosticBuffer::kMaxBatchBytes, "long records exceed batch size");
    require(batch.through < 70 && batch.text.find("...") != std::string::npos,
            "long records are not bounded");
    const std::u16string decoded = utf16FromUtf8(batch.text);
    require(decoded.find(u"桌...") != std::u16string::npos, "truncation split a UTF-8 codepoint");
    buffer.setSession(15);
    buffer.append("first\nsecond\rthird", 15);
    auto clean = buffer.batch();
    require(std::count(clean.text.begin(), clean.text.end(), '\n') == 1,
            "producer message injected additional protocol lines");
}

struct DiagnosticTestBundle { int64_t session = 0; int64_t ack = 0; std::string message; };
int diagnosticBundleDrops = 0, diagnosticResolverDrops = 0, diagnosticErrorDrops = 0;
int diagnosticReplyMode = 0;
int64_t diagnosticServerSession = 40;
std::string diagnosticReceived;

RustResult48 diagnosticCall(void *, const char *authority, size_t authorityLength,
        const char *method, size_t methodLength, const RustOptionStringView *argument, void *extras) {
    require(std::string(authority, authorityLength) == DiagnosticsProviderAuthority,
            "ContentResolver received a URI instead of bare authority");
    require(argument != nullptr && argument->tag == 1, "call argument must be None");
    const bool append = std::string(method, methodLength) == "append_native";
    require(append == (extras != nullptr), "state/batch extras protocol mismatch");
    auto *submitted = static_cast<DiagnosticTestBundle *>(extras);
    if (append) diagnosticReceived = submitted->message;
    RustResult48 result{};
    if (diagnosticReplyMode == 2) {
        result.data[8] = 1; // Ok(None)
        return result;
    }
    auto *response = new DiagnosticTestBundle{diagnosticServerSession,
            append && diagnosticReplyMode != 1 && submitted->session == diagnosticServerSession
                    ? submitted->session : 0, {}};
    std::memcpy(result.data + 16, &response, sizeof(response));
    return result;
}

DiagnosticsTransport diagnosticTransport() {
    return {reinterpret_cast<void *>(0x123),
        +[](void *runtime) -> void * {
            require(runtime == reinterpret_cast<void *>(0x123), "resolver lost retained Shared<Runtime>");
            return reinterpret_cast<void *>(0x456);
        },
        +[](void *) { ++diagnosticResolverDrops; }, diagnosticCall,
        +[]() -> void * { return new DiagnosticTestBundle; },
        +[](void *bundle) { ++diagnosticBundleDrops; delete static_cast<DiagnosticTestBundle *>(bundle); },
        +[](void *bundle, const char *key, size_t size) -> RustOptionI64 {
            auto *value = static_cast<DiagnosticTestBundle *>(bundle);
            return {0, {}, std::string(key, size) == "session" ? value->session : value->ack};
        },
        +[](void *bundle, const RustRString *key, int64_t value) {
            require(*reinterpret_cast<std::u16string *>(key->first) == u"session", "bad session key");
            delete reinterpret_cast<std::u16string *>(key->first);
            static_cast<DiagnosticTestBundle *>(bundle)->session = value;
        },
        +[](void *bundle, const RustRString *key, const RustOptionString *value) {
            require(*reinterpret_cast<std::u16string *>(key->first) == u"message", "bad message key");
            auto *text = reinterpret_cast<std::u16string *>(value->value.first);
            // This integration case deliberately sends ASCII. UTF-8 limits are tested above.
            static_cast<DiagnosticTestBundle *>(bundle)->message.assign(text->begin(), text->end());
            delete reinterpret_cast<std::u16string *>(key->first);
            delete text;
        },
        +[](const uint16_t *value, size_t length) -> RustRString {
            return {reinterpret_cast<uintptr_t>(new std::u16string(
                    reinterpret_cast<const char16_t *>(value), length)), 0, 0, 0};
        }};
}

void diagnosticsTransportRetriesAndRejectsStaleEpochs() {
    auto transport = diagnosticTransport();
    diagnosticReplyMode = 0;
    diagnosticServerSession = 40;
    diagnosticBundleDrops = diagnosticResolverDrops = 0;
    auto state = transport.request();
    require(state.valid && state.session == 40, "state response not decoded");
    g_diagnostics.setSession(state.session);
    nativeLog(ANDROID_LOG_WARN, "ActionGridNative", "placement failure node=%d", 123);
    auto batch = g_diagnostics.batch();
    require(batch.text.find("placement failure node=123") != std::string::npos
                    && batch.text.find("pid=") != std::string::npos, "native log did not reach the queue");
    diagnosticReplyMode = 1; // Provider alive but disk write failed.
    sendDiagnosticsBatch(transport, batch);
    require(g_diagnostics.batch().text == batch.text, "unacknowledged records were lost");
    diagnosticReplyMode = 2; // Transport gives no usable response.
    sendDiagnosticsBatch(transport, batch);
    require(g_diagnostics.batch().text == batch.text, "missing response consumed records");
    diagnosticReplyMode = 0;
    sendDiagnosticsBatch(transport, batch);
    require(g_diagnostics.batch().text.empty() && diagnosticReceived == batch.text,
            "acknowledged batch not delivered intact");
    nativeLog(ANDROID_LOG_INFO, "ActionGridNative", "old epoch");
    batch = g_diagnostics.batch();
    diagnosticServerSession = 41; // Disable and re-enable before old batch arrives.
    sendDiagnosticsBatch(transport, batch);
    require(g_diagnostics.session() == 41 && g_diagnostics.batch().text.empty(),
            "server epoch change did not invalidate old records");
    require(diagnosticResolverDrops == 5 && diagnosticBundleDrops == 8,
            "resolver or extras/response Bundle lifetime mismatch");
    g_diagnostics.setSession(0);
}

void diagnosticsResultOwnership() {
    auto transport = diagnosticTransport();
    int before = diagnosticBundleDrops;
    RustResult48 alternate{};
    alternate.data[0] = 1;
    auto *bundle = new DiagnosticTestBundle{88, 88, {}};
    std::memcpy(alternate.data + 32, &bundle, sizeof(bundle));
    auto reply = transport.decode(alternate);
    require(reply.valid && reply.session == 88 && diagnosticBundleDrops == before + 1,
            "alternate Option<Bundle> is leaked or decoded at the wrong offset");
    std::array<uintptr_t, 4> vtable{};
    vtable[3] = reinterpret_cast<uintptr_t>(+[](void *) { ++diagnosticErrorDrops; });
    RustVec message{reinterpret_cast<void *>(0x789), 0, 0, vtable.data()};
    RustResult48 failure{};
    failure.data[0] = 1;
    int32_t code = -7;
    std::memcpy(failure.data + 8, &code, sizeof(code));
    std::memcpy(failure.data + 16, &message, sizeof(message));
    reply = transport.decode(failure);
    require(!reply.valid && reply.error == -7 && diagnosticErrorDrops == 1,
            "error result did not release its owned RString exactly once");
}

void diagnosticsRecordsHomeAndPlacementFailures() {
    g_diagnostics.setSession(95);
    returningHomeKeepsActionsOnNodesBuiltDuringFocusCallback();
    prepareGrid();
    onPerformAction(reinterpret_cast<void *>(1), kVirtualNodeBase + kVirtualNodeCount - 1, 16, nullptr);
    const std::string text = g_diagnostics.batch().text;
    require(text.find("icon actions node=") != std::string::npos
                    && text.find("window focus 0 -> 1") != std::string::npos,
            "return-to-Home regression has no exported injection diagnostics");
    require(text.find("occupancy(x=occupied,.=free)") != std::string::npos
                    && text.find("handled=0") != std::string::npos,
            "grid state or failed double-tap result missing from diagnostics");
    g_diagnostics.setSession(0);
}

void diagnosticsRecordsPlacementEvidence() {
    struct Cleanup { ~Cleanup() { g_diagnostics.setSession(0); } } cleanup;
    g_diagnostics.setSession(96);
    placementRefreshNotifiesReaderBeforeVisibilityWait();
    const std::string success = g_diagnostics.batch().text;
    require(success.find("move source node=90 label=美团外卖 id=101") != std::string::npos
                    && success.find("move target id=101 container=-100 page=3 cell=1,0") != std::string::npos
                    && success.find("move visibility target id=101 label=美团外卖 page=3 cell=1,0 bounds=100,0,200,100") != std::string::npos,
            "exported logs cannot identify the source, destination, and physical target");
    require(success.find("move db stage=after_reload matched=1") != std::string::npos
                    && success.find("move candidate id=101 node=91") != std::string::npos
                    && success.find("move visibility result id=101 visible=1") != std::string::npos,
            "exported logs cannot distinguish persisted coordinates from visible nodes");
    g_diagnostics.setSession(97);
    placementRechecksDatabaseAfterReload();
    const std::string failure = g_diagnostics.batch().text;
    require(failure.find("move db stage=after_reload matched=0") != std::string::npos
                    && failure.find("want=-100/3/1,0 actual=-100/3/0,0") != std::string::npos
                    && failure.find("persisted=0 visible=1") != std::string::npos,
            "reload changing the model is indistinguishable from a reader refresh failure");
}

void diagnosticsBoundsPlacementCandidates() {
    prepareGrid();
    struct Cleanup {
        ~Cleanup() { endPendingPlacement(); g_diagnostics.setSession(0); }
    } cleanup;
    g_diagnostics.setSession(98);
    beginPendingPlacement({{{101, kDesktopContainer, 3, 1, 0, 1, 1, 0},
            "QQ", {100, 0, 200, 100}, false}});
    for (int index = 0; index < 100; ++index) observePendingPlacement(g_workspace[1]);
    endPendingPlacement();
    const std::string text = g_diagnostics.batch().text;
    size_t records = 0;
    for (size_t at = text.find("move candidate"); at != std::string::npos;
            at = text.find("move candidate", at + 1)) ++records;
    require(records == 12 && text.find("observations=100 label_hits=100") != std::string::npos
                    && text.find("bounds_ok=0") != std::string::npos,
            "repeated reader queries either flood diagnostics or lose mismatch evidence");
}
}
