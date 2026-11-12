# Signing key for the phone app

`helper-key.p12` signs every build of the phone app, so that a new build installs
over the old one and keeps its permissions and settings.

The key and its password (`watch-helper`) are in this public repository on purpose:
it keeps the GitHub build self-contained. The trade-off is that anyone could sign an
app with the same key, so **only install phone app builds from this repository's own
Releases page**.

To use a private key instead, generate a new keystore, keep it out of the repository,
and feed it to the build through repository secrets. Switching keys means uninstalling
the app once and installing the newly signed build.
