# Security Crypto (Tink)
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

# Keep our domain models for serialization
-keep class app.passwordmanager.domain.** { *; }
-keepattributes *Annotation*, Signature, InnerClasses
