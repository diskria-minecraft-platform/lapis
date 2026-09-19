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
@KMixin(AdvancementsScreen::class, Env.Client)
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

For real-world examples, check out my mod repositories: [advancements-fullscreen](https://github.com/diskria-minecraft/advancements-fullscreen) and [advancements-search](https://github.com/diskria-minecraft/advancements-search). I develop them as dogfooding projects for this KSP plugin—they're actively running in production right now, even though version 1.0.0 hasn't officially launched yet.

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
    mixinConfig = file("src/main/resources/$modId.mixins.json")
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
2. KSP Layer: Reads your Kotlin code and generates a rock-solid Java Duck Interface and a thin Java Mixin inside your
   build directory (build/generated/ksp).
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

### Roadmap / Coming Soon

- **Automated AW/AT**: Seamlessly call non-public members in Minecraft source code without touching
  `.accesswidener` files. The FIR plugin will make them visible in your IDE on the fly and generate required AW/AT rules
  automatically at build time.
- **Type-Safe Declarative Hooks**: Replacement of raw string descriptors (such as `"repositionElements()V"`) with
  type-safe references to target classes and methods, integrated with FIR symbol resolution.
- **Early Injection & Shadow Validation**: Compile-time verification of `@KShadow` targets and injection signatures via
  FIR checkers to catch invalid descriptors, type mismatches, and missing targets before launch.
