# Third-party notices

## Android game runtime integration

See `docs/ANDROID_GAME_RUNTIME.md` for tested boundaries and rebuilding information. Exact download URLs and SHA-256 are in `nativebridge/third_party/game-runtime-lock.json`; original license assets are packaged under `assets/licenses` without changing their contents.

- Official LWJGL 3.3.3 Java bindings: Maven Central, BSD-3-Clause. The Android JNI core/OpenGL/STB/TinyFD subset is extracted from `FCL/libs/lwjgl-3.3.3-natives-release.aar` at FoldCraftLauncher commit `cd14d179ce22a806c9eda6353836e5abfa93cf30` (archive SHA-256 `ec26f2008e5c4b73605d5f401e8f3df8f4e0eb74d2e66fbba7d349a1f530552f`). Upstream Android binding source/build workflow: https://github.com/FCL-Team/lwjgl3/tree/ddf2a2aa31b578cc89bc5644fba5057f56d66867 . Included notices also cover libffi MIT, TinyFD zlib, Khronos and STB's dual public-domain/MIT terms. No FCL launcher Java implementation, modified GLFW Java classes, or artwork is imported.
- GLFW 3.3.8 public header at commit `7482de6071d21db77a7236155da44c172a7f6c9e`: zlib license. The original header is retained unchanged. `android_glfw.cpp` is an original Android host adapter, not upstream GLFW or GPL launcher code.
- JNA 5.13.0 official Android AAR: Apache-2.0 OR LGPL-2.1; this integration selects Apache-2.0. Its original LICENSE, AL2.0 and native libffi license/build-tool notices accompany the APK. Original JNA Java/native code is not modified.
- OpenAL Soft 1.23.1 at commit `d3875f333fb6abe2f39d82caca329414871ae53b`: LGPL-2.0-or-later plus file-level notices. The complete original source, original COPYING and all headers are retained in `nativebridge/third_party/openal-soft`. It is built as a separate shared library with the OpenSL ES backend (AOSP 2011 Apache-2.0 source); applicable BSD notices and the original license text accompany the APK. No upstream OpenAL source is edited.

OpenAL's LGPL obligations remain applicable when loaded through LWJGL/dlopen. APK distribution must include the exact corresponding library source/build information and the application materials/permissions needed for users to rebuild/relink and debug library modifications under the applicable terms; a module boundary alone is insufficient. Source location: https://github.com/kcat/openal-soft/tree/d3875f333fb6abe2f39d82caca329414871ae53b ; governing text: https://www.gnu.org/licenses/old-licenses/lgpl-2.0.html . This project has not selected a license for its own code and does not claim that a closed-source release is authorized. Do not distribute only the APK while omitting these requirements.

No Minecraft/JRE/account content is bundled by this integration. The original project license files and copyright headers are unchanged.

## Gradle Wrapper 8.13

- Official source: https://github.com/gradle/gradle/tree/v8.13.0
- Files: `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`.
- License: Apache License 2.0. The original startup-script copyright and license header is preserved. The original upstream license text and dependency notices are retained unmodified in `gradle/wrapper/LICENSE-Gradle.txt`. The JAR also contains `META-INF/LICENSE`.
- Local changes to `gradlew.bat`: derive project-local cache, user-home and temporary paths; pass the project directory explicitly; discover the installed project-local Android SDK when ANDROID_HOME is unset. No launcher code was copied.
- Verified Wrapper JAR SHA-256: `81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f`.
- Distribution checksum is pinned in `gradle-wrapper.properties`; source: https://services.gradle.org/distributions/gradle-8.13-bin.zip.sha256.

This tool remains subject to its upstream terms; preserve applicable copyright, license, modification notices and any required notices when redistributing it. It does not choose the license of this project's original code.

## Android/Kotlin dependencies

AndroidX/Compose and Kotlin are referenced through official Maven repositories. Their upstream licenses and included notices apply; the dependency and artifact license inventory must be reviewed before distribution. No SDK, JRE or game files are committed by this increment.

## Launcher/rendering references

No GPL launcher application source or artwork from Amethyst-Android, FoldCraftLauncher, ZalithLauncher2 or PojavLauncher is copied. The separately licensed LWJGL Android JNI subset obtained from FoldCraftLauncher's pinned dependency archive is documented below; it is not launcher application code. Zink and VirGL remain reserved interfaces. Do not assume a module/JNI boundary removes license obligations. This project does not yet select a distribution license for its own original code.

## Local Android SDK build environment

The ignored `.android-sdk/` directory is a local build tool installation, not application content. Command-line Tools 19.0, Android Platform 35, Build Tools 35.0.0 and Platform Tools are obtained from Google through the official SDK manager. Their original license and notice files remain in the installed packages. The required Android SDK license was accepted as part of the requested setup, with the installer log and license text retained locally. No SDK binaries or license terms are copied into application source or the APK by this change.

Official tool documentation: https://developer.android.com/tools/sdkmanager

## Input overlay dependencies

Gson 2.11.0 is used for JSON encoding/decoding, not copied source: https://github.com/google/gson/tree/gson-parent-2.11.0. Its Apache-2.0 license was checked at that exact tag and retained unchanged in `input/src/main/assets/third_party/gson-2.11.0.txt`.

kotlinx.coroutines 1.10.2 is used for asynchronous app-private storage and input state: https://github.com/Kotlin/kotlinx.coroutines/tree/1.10.2. Its Apache-2.0 license was checked at that exact tag and retained unchanged in `input/src/main/assets/third_party/kotlinx-coroutines-1.10.2.txt`. These assets are included in the APK so the license copies accompany the dependency binaries. Preserve applicable upstream notices when redistributing.

The overlay, procedural debug background, input router and layout preset are original implementation. No GPL launcher source, private iOS resources or Minecraft files are included; original project/Wrapper license files were not changed.

## Download center

jsoup 1.18.3 is used as an HTML parser through Maven. Its MIT license, including the original attribution, is retained unmodified in `core/src/main/assets/third_party/jsoup-1.18.3.txt` and included in the APK. Upstream: https://github.com/jhy/jsoup/tree/jsoup-1.18.3.

AndroidX WorkManager 2.10.1 is used for durable background tasks, under AndroidX's Apache-2.0 license. Gson and kotlinx.coroutines use the previously recorded versions/licenses; their existing license assets are retained. Preserve applicable upstream copyright and notice information for distribution.

PCL / Plain Craft Launcher, referenced at the user's request, uses custom terms: https://github.com/Hex-Dragon/PCL2/blob/main/LICENCE. No PCL source or resources were copied. Only the general category/filter/task interaction concept informed this independently written implementation; there is no affiliation. The project license and original headers were not changed.

MCMOD is used as the displayed metadata source and user-driven web browser. Native reads are limited to public numeric class/modpack pages permitted by the site's current robots policy; native search and jump crawling are not implemented. Only public author project targets are parsed. Actual artifacts are obtained from Mojang, Modrinth, or user-selected original download sources. No game, mod, modpack, shader or resource-pack content is bundled or redistributed by this project. Their original author licenses remain applicable to the files the user downloads.

## Offline accounts and landscape navigation

The auth module references the same Gson 2.11.0 Maven dependency already recorded above. Its existing license asset remains included through input; no new dependency version or copied third-party source is introduced. Offline account validation, persistence and the landscape shell are original code. PCL's top-level categories and left account area are structural references only; no PCL code, icons, background, screenshot or other artwork is bundled. Existing license texts and copyright headers are unchanged.

## GL4ES native rendering

GL4ES is compiled from the original MIT-licensed source at https://github.com/ptitSeb/gl4es, commit `a025615bc79ac43ec911dced8fe5b8eb45a0b72d`. Only src, include, the root CMakeLists.txt, version.h and LICENSE were retained; none of those upstream files was edited. The archive SHA-256 and subset are recorded in `renderer/third_party/gl4es-source.json`. Existing source headers and the original LICENSE are retained. The root MIT license and file-level third-party permission notices accompany the APK in renderer assets. Preserve these copyright/permission notices when redistributing the binaries or source. This permissive dependency does not select a license for this project's own code.

The native bridge, runtime ZIP validator/importer, IPC smoke test and EGL Surface test are independently written. No Pojav/FCL/Prism GPL implementation is copied. Prism's public authentication requests were consulted only to confirm protocol endpoints and response fields; its GPL-3.0 implementation was not imported or translated.

## User-imported Android OpenJDK

No JVM binary, JRE archive or Minecraft content is included in source or the APK. Users may import their own compatible Android/Bionic OpenJDK ZIP after checking its source, hash and applicable license. The importer retains all files and requires an existing LICENSE or legal directory; the conversion utility expands archive-local file links while preserving notices. OpenJDK artifacts can include GPL-2.0-with-Classpath-exception components and additional notices; inspect the exact artifact and its source/distribution obligations before redistributing it. A JNI/module boundary does not automatically remove license duties.

NDK r27c and CMake 3.22.1 are build tools installed only in the ignored project-local Android SDK, under their original terms. Official archive checksums and source URLs are recorded in the native installation receipt. The compiler/build-tool executables are not bundled in the application. The bridge links the NDK C++ runtime; its upstream Apache-2.0-with-LLVM-exception and included permission notices are copied unchanged from the pinned NDK toolchain NOTICE to `nativebridge/src/main/assets/third_party/ndk-r27c-llvm-NOTICE.txt` and accompany the APK.

## Microsoft protocol integration

Device-code OAuth follows https://learn.microsoft.com/en-us/entra/identity-platform/v2-oauth2-device-code. Users supply their own registered public-client application ID. Xbox/Minecraft services decide authorization and ownership; no other launcher's client ID or secret is embedded. Gson and kotlinx.coroutines reuse the existing versions and retained license assets. Session encryption uses Android Keystore/AES-GCM platform APIs, with no new crypto library.

## Mod loader catalogs and user-requested downloads

The Forge/Fabric/NeoForge selector, catalog parsers, archive validation and Fabric profile preparation are original code. No launcher or mod-loader implementation was copied or translated, no new dependency was added, and no loader/game binaries are bundled in the APK. Catalogs and artifacts are requested directly from the upstream Meta/Maven services; downloaded JARs remain intact, including their embedded license and attribution files. Installer processors are not executed by this increment.

Upstream license files inspected before integration:

- Fabric Loader: Apache License 2.0, https://github.com/FabricMC/fabric-loader/blob/master/LICENSE.
- Minecraft Forge: LGPL 2.1 with additional third-party notices and qualifications in https://github.com/MinecraftForge/MinecraftForge/blob/1.20.x/LICENSE.txt. Those qualifications, including MCP data terms, must not be treated as a blanket permission to redistribute all artifacts.
- NeoForge: LGPL 2.1, https://github.com/neoforged/NeoForge/blob/1.21.1/LICENSE.txt; individual components and the installer may have their own notices. The exact downloaded artifact's terms remain applicable.

This increment downloads user-selected originals into app-private storage and preserves the archives; it does not relicense or redistribute their contents as project source. Any future bundling, modification, linking or redistribution requires reviewing the exact artifact and all its included notices/source obligations. Existing project LICENSE files and copyright headers were not modified. A module boundary is not a license exception.

## Launch planning and JVM main probe

Version inheritance, local launch identity validation, argument/classpath preparation, diagnostic UI and the JNI main invocation are independently written. The JNI specification was consulted for API contracts: https://docs.oracle.com/en/java/javase/17/docs/specs/jni/invocation.html. No sample implementation was copied. LWJGL's upstream license was inspected during feasibility research, but no LWJGL source or binary is included in this increment; its Android platform integration remains pending.

`launcher/src/jvmSmoke/java` contains the project's own small Java probe. Gradle compiles it with `--release 8` into the APK asset `jvm/launchapk-smoke.jar`; it has no third-party dependency, JVM binary, game class or game asset. The probe exercises main invocation and Unicode argument transport only. Existing runtime/JVM import terms and GL4ES notices above remain applicable. No existing LICENSE or copyright header was changed, and no new library dependency was added.
