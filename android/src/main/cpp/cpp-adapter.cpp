#include <jni.h>
#include "novasteraoss_nitrocalendarOnLoad.hpp"

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
  return margelo::nitro::novasteraoss_nitrocalendar::initialize(vm);
}
