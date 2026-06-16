# Android HexView

A lightweight Android custom `HexView` component for viewing binary data in hex and ASCII form.

## Features

- Hex + ASCII dual-column display
- Byte selection with draggable handles
- Copy selection as hex or ASCII text
- File, URI, and in-memory byte sources
- Vertical scrolling with custom scrollbar
- Designed as an Android library module

## Module

This project is currently structured as an Android library and can be published as a reusable component.

## Usage

### Add dependency

If you publish it with JitPack:

```gradle
repositories {
    maven { url 'https://jitpack.io' }
}

dependencies {
    implementation 'com.github.JunGe-Y:Android-Hexview:1.0.0'
}
```

### In layout

```xml
<com.junge.hexview.HexView
    android:id="@+id/hexView"
    android:layout_width="match_parent"
    android:layout_height="wrap_content" />
```

### In code

```java
hexView.setBytesPerRow(16);
hexView.setBytes(data);
// or
hexView.setFile(file);
// or
hexView.setUri(uri);
```

## Publishing notes

Recommended coordinates:

- Group: `io.github.yourname`
- Artifact: `android-hexview`
- Version: `1.0.0`

## License

Apache-2.0
