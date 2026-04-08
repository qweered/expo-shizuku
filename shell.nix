{ pkgs ? import <nixpkgs> {
    config.allowUnfree = true;
    config.android_sdk.accept_license = true;
  }
}:

let
  androidComposition = pkgs.androidenv.composeAndroidPackages {
    platformVersions = [ "36" ];
    buildToolsVersions = [ "36.0.0" ];
    includeNDK = false;
    includeSources = false;
    includeSystemImages = false;
    includeEmulator = false;
    extraLicenses = [
      "android-sdk-license"
      "android-sdk-preview-license"
    ];
  };

  androidSdk = androidComposition.androidsdk;
in
pkgs.mkShell {
  buildInputs = with pkgs; [
    jdk17
    androidSdk
    gradle
  ];

  shellHook = ''
    export ANDROID_HOME="${androidSdk}/libexec/android-sdk"
    export JAVA_HOME="${pkgs.jdk17}"
  '';
}
