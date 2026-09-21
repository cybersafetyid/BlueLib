# BlueLib consumer rules.
#
# The library needs no keep rules of its own:
#
# * All platform calls go through the Android SDK, which R8 never rewrites.
# * No reflection is used anywhere in BlueLib: callbacks are anonymous subclasses of platform types,
#   which R8 keeps because the platform references them.
# * The public API is consumed as Kotlin code, so member shrinking follows the consumer's own rules.
#
# What matters instead is that consumers do not lose the *classes* they subclass. Nothing to do here,
# but the file is kept (and referenced from the AAR) as the documented place to add rules if a future
# release ever needs one.
