# Add consumer ProGuard rules here.
# JitPack / Maven consumers will inherit these rules.
# Public API for library consumers.
-keep public class com.junge.hexview.HexView { *; }
-keep public interface com.junge.hexview.HexSource { *; }
-keep public class com.junge.hexview.InMemoryHexSource { *; }
