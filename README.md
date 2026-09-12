# Lapis

Idiomatic, zero-boilerplate Kotlin Mixins for Minecraft modding.

[![Gradle Plugin Portal](https://img.shields.io/gradle-plugin-portal/v/io.github.diskria.lapis.svg?label=Gradle+Plugin+Portal&style=for-the-badge)](https://plugins.gradle.org/plugin/io.github.diskria.lapis) [![Maven Central](https://img.shields.io/maven-central/v/io.github.diskria/lapis-ksp.svg?label=Maven+Central&style=for-the-badge)](https://central.sonatype.com/artifact/io.github.diskria/lapis-ksp) [![Maven Central](https://img.shields.io/maven-central/v/io.github.diskria/lapis-annotations.svg?label=Maven+Central&style=for-the-badge)](https://central.sonatype.com/artifact/io.github.diskria/lapis-annotations) [![License: MIT](https://img.shields.io/static/v1?label=License&message=MIT&color=yellow&style=for-the-badge)](https://spdx.org/licenses/MIT)

---

> [!WARNING]
> **Work in Progress (WIP):** Lapis is currently under active development. The API is unstable and subject to breaking
changes prior to the `1.0.0` release. Full documentation and stable artifacts will be published alongside `1.0.0`.

Lapis is a next-generation Kotlin compiler plugin and KSP framework designed to eliminate the friction, boilerplate, and
JVM quirks of writing Sponge Mixins in Kotlin. Write clean, OOP-driven code with instant IDE auto-completion and let
Lapis handle the bytecode orchestration under the hood.

## Key Features

- Synthetic Extension Methods: Define extensions on Minecraft classes without manual Duck interfaces or casting clutter.
  They show up directly in your IDE auto-completion.
- True Object-Oriented Inheritance: Inherit your mixins from custom abstract base classes to share logic across multiple
  patches—solving a long-standing Sponge Mixin limitation.
- Bulletproof `@KShadow`: Simply copy Java field/method modifiers (private static final) directly. Lapis handles all
  Sponge Mixin requirements, synthetic error throws, and annotations automatically.
- Auto-merged mixins.json: Stop manually updating registration files. The Gradle plugin discovers generated mixins and
  merges them into your resource config automatically.
- Smart Package Isolation (LCP): Organizes generated code using the Longest Common Prefix (LCP) rule or a flat package
  structure to keep your build clean and prevent package-private leaks.
- Extension Receivers & `@Origin`: Inject target class instances cleanly into constructors as `@Origin` or use the
  target type as an extension receiver within injections.

## 🚀 Quick Showcase

```kotlin
@KMixin(AdvancementsScreen::class)
abstract class AdvancementsScreenMixin(@Origin val screen: AdvancementsScreen) : BaseScreenPatch() {

    // 1. Declarative shadow: Copy modifiers as-is
    @KShadow(PRIVATE, FINAL)
    abstract var tabs: Map<AdvancementHolder, AdvancementTab>

    // 2. Synthetic extension: Visible on AdvancementsScreen everywhere
    @Extension
    val fullscreenWindowWidth: Int
        get() = screen.width - SCREEN_MARGIN * 2

    // 3. Standard injections
    @WrapMethod(method = ["repositionElements()V"])
    fun calculateOnReposition(original: Operation<Void>) {
        original.call()
        tabs.values.forEach { it.centered = false }
    }
}
```

## 🛠️ Configuration

> [!NOTE]
> Gradle artifacts will be available upon the `1.0.0` release. The DSL below represents the target configuration.

Add Lapis to your `build.gradle.kts`:

```kotlin
plugins {
    id("io.github.diskria.lapis") version "1.0.0"
}

lapis {
    uniqueModPrefix = "$modId$" // Used as a unique prefix in generated duck methods
    sourceSets.register("main") {
        mixinConfig = file("src/main/resources/$modId.mixins.json")
    }
}
```

### Architecture: The "Escape from Mixin" Concept

In Lapis, the Kotlin class you write is not a Sponge Mixin—it is a Pure Kotlin Patch.

Because a Kotlin Compiler Plugin cannot directly output Java source files required by the Sponge Mixin Annotation
Processor on runtime, Lapis decouples the responsibility across three layers:

```
[ Your Kotlin Patch (@KMixin) ]
              │
              ├─────── (FIR/IR Plugin) ──────► Replaces extension calls with zero-cost casts
              │
              └────────── (KSP) ─────────────► Generates Java Duck Interface
                                                     │
                                                     ▼
                                           [ Generated Java Mixin ] ◄── (Processed by Sponge)
                                                     │
                                                     ▼
                                           [ Kotlin Impl Bridge ]
```

1. Your Kotlin Patch (`@KMixin`): Holds pure business logic, extension properties, and custom OOP inheritance.
2. KSP Layer: Reads your Kotlin code and generates a rock-solid Java Duck Interface and a thin Java Mixin inside your build directory (build/generated/ksp).
3. Compiler Plugin (FIR/IR): Exposes synthetic methods to your IDE during editing and inlines calls at compile-time
   directly into zero-cost interface casts `((Duck) instance).method()`.

### Why this design?

- **Real Type System:** Your patch behaves like a regular Kotlin class. Extend abstract handlers, implement custom
  interfaces, and hold complex state natively.
- **Clean Runtime Classes:** Your custom interfaces stay inside your Patch and do not pollute the target Minecraft class
  at runtime. The generated Java Mixin only carries the lightweight bridges it actually needs.
- **Automated Type Interop Layer:** Lapis builds a seamless bridge between Kotlin and Java types under the hood. You
  write idiomatic Kotlin types and nullability, and Lapis automatically translates them into valid Java Mixin
  signatures, `@Override` rules, and Duck interface casts without any manual glue code.

## F.A.Q

#### Where is `@Unique` and how are members isolated?

You no longer need `@Unique`. Since your `@KMixin` is a standalone Kotlin patch class, all of its properties, methods, and state are isolated by default. Non-annotated code remains in your patch class and never touches the target Minecraft class directly. The generated Java Mixin contains only the minimal bridge code required by Sponge.

#### How clean is the exported bytecode (Mixin Export)?

Target Minecraft classes remain lightweight and readable. Unlike standard Mixin, where custom state and unique methods inflate the target class at runtime, Lapis routes logic through your patch instance. The resulting class contains zero unneeded fields or unique methods from your patch.

#### Will Lapis break when new Sponge Mixin or Mixin Extras annotations are introduced?

No. By default, Lapis automatically identifies and transfers all runtime annotations (such as `@Inject`, `@Redirect`, or `@WrapOperation`) from your patch to the generated Java Mixin. If a new annotation is released or you use a custom framework, you can explicitly configure the target annotation list in the Gradle DSL via `lapis.mixinAnnotations.add("com.example.CustomMixinAnnotation")`.

#### What if I need custom Sponge features or want to bypass the automated code generation (Escape Hatch)?

If you need specific Sponge attributes (such as priority, remap, ⁠aliases⁠, ⁠prefix⁠, or custom annotation parameters) that are not exposed by Lapis annotations, or if the automatic generation does not fit a specific edge case, you can simply attach standard Sponge annotations (like ⁠`@Shadow`⁠ or ⁠`@Mixin`⁠) directly alongside their Lapis equivalents (`⁠@KShadow`⁠, ⁠`@KMixin`⁠). When Lapis detects a standard Sponge annotation on a declaration, KSP copies it as-is without applying automatic defaults, while all other framework features continue to apply based on what is passed into the ⁠`@K⁠`-annotation.

#### What is Lapis doing behind the scenes?

Lapis automates the exact patterns you would otherwise write by hand—generating standard Duck interfaces, Java-compatible Mixin bridges, performing target casts, and updating JSON configs. All generated code is placed directly in your project's `⁠build/generated/ksp/`⁠ directory, where you can inspect it at any time to verify that it produces clean, standard Mixin architecture without any hidden runtime magic.

### Roadmap / Coming Soon

- **Automated AW/AT**: Seamlessly call non-public members in Minecraft source code without touching
  `.accesswidener` files. The FIR plugin will make them visible in your IDE on the fly and generate required AW/AT rules
  automatically at build time.
- **Type-Safe Declarative Hooks**: Replacement of raw string descriptors (such as `"repositionElements()V"`) with
  type-safe references to target classes and methods, integrated with FIR symbol resolution.
- **Early Injection & Shadow Validation**: Compile-time verification of `@KShadow` targets and injection signatures via
  FIR checkers to catch invalid descriptors, type mismatches, and missing targets before launch.

---

## License

This project is licensed under the [MIT License](https://spdx.org/licenses/MIT).
