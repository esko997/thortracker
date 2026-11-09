// Minimal stand-in for Stella's Serializable.hxx and Serializer.hxx, written for Thor Tracker (not part of Stella):
// the Serializable interface AudioChannel derives from (virtual save/load, as Stella 7.0 declares them) and a
// Serializer over a fixed in-object buffer, so tia/tia_stella.cpp can read a channel's ten state bytes through
// AudioChannel::save (diagnostics and tests). Bools are stored as 0/1 (Stella's own Serializer uses other patterns;
// nothing here is a Stella state file). Never throws, never allocates: writes past the buffer are dropped, reads
// past the written bytes return 0.
#ifndef SERIALIZABLE_HXX
#define SERIALIZABLE_HXX

#include "bspf.hxx"

class Serializer {
  public:
    static constexpr int kCapacity = 16;

    Serializer() = default;

    void putByte(uInt8 value) {
        if (myWritten < kCapacity) myData[myWritten++] = value;
    }
    void putBool(bool value) { putByte(value ? 1 : 0); }

    uInt8 getByte() { return myRead < myWritten ? myData[myRead++] : 0; }
    bool getBool() { return getByte() != 0; }

    int size() const { return myWritten; }
    const uInt8* data() const { return myData; }
    void rewind() { myRead = 0; }

  private:
    uInt8 myData[kCapacity]{};
    int myWritten{0};
    int myRead{0};
};

class Serializable {
  public:
    Serializable() = default;
    virtual ~Serializable() = default;

    Serializable(const Serializable&) = default;
    Serializable(Serializable&&) = default;
    Serializable& operator=(const Serializable&) = default;
    Serializable& operator=(Serializable&&) = default;

    virtual bool save(Serializer& out) const = 0;
    virtual bool load(Serializer& in) = 0;
};

#endif  // SERIALIZABLE_HXX
