# The player service creates the app's MediaLibraryProvider by reflection from the
# class name in manifest meta-data, which R8 can't see. Keep implementations' constructors.
-keep class * implements co.broadcastapp.muckabout.library.MediaLibraryProvider {
    public <init>();
}
