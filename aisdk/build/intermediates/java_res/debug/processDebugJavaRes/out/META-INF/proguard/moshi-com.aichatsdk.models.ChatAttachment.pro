-if class com.aichatsdk.models.ChatAttachment
-keepnames class com.aichatsdk.models.ChatAttachment
-if class com.aichatsdk.models.ChatAttachment
-keep class com.aichatsdk.models.ChatAttachmentJsonAdapter {
    public <init>(com.squareup.moshi.Moshi);
}
-if class com.aichatsdk.models.ChatAttachment
-keepnames class kotlin.jvm.internal.DefaultConstructorMarker
-if class com.aichatsdk.models.ChatAttachment
-keepclassmembers class com.aichatsdk.models.ChatAttachment {
    public synthetic <init>(java.lang.String,java.lang.String,java.lang.String,int,kotlin.jvm.internal.DefaultConstructorMarker);
}
