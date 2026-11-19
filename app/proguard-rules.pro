# Google Play Billing Library
-keep class com.android.vending.billing.** { *; }
-keep class com.android.billingclient.api.** { *; }
-dontwarn com.android.billingclient.**

# Google Mobile Ads (consumer rules ship with the SDK; kept here for clarity)
-keep public class com.google.android.gms.ads.** { public *; }
-dontwarn com.google.android.gms.**

# Keep line numbers for readable crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
