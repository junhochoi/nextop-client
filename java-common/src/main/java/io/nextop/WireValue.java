package io.nextop;

import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.collect.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import io.nextop.org.apache.commons.codec.binary.Base64OutputStream;
import org.apache.commons.collections.Transformer;
import org.apache.commons.collections.map.TransformedMap;

import javax.annotation.Nullable;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.util.*;


// TODO
// wire value v2:
// - fixed size index (23-bit)
// - incremental headers
// -- lru maintainenace to keep within a fixed size
// -- store the compressed value in the lru map
// -- never compress int* of float*
// - use ByteBuffer in API instead of byte[]

// more efficient codec than text, that allows a lossless (*or nearly, for floats) conversion to text when needed
// like protobufs/bson but focused on easy conversion to text
// conversion of text is important when constructing HTTP requests, which are isSend-based
// TODO do compression of values and objects keysets by having a header in the front. up to the 127 that save the most bytes to compress
// TODO long term the compression table can be stateful, so transferring the same objects repeatedly will have a savings, like a dynamic protobuf
// TODO wire value even in a simple form will increase speed between cloud and client, because binary will be less data and faster to parse on client
// TODO can always call toString to get JSON out (if type is array or object)

// FIXME look at sizes if the lut is stateful, and the lut didn't need to be resent each time

// FIXME memory and compressed wire values should have same hashcode and equals


// FIXME everywhere replace byte[] with ByteBuffer

public abstract class WireValue {
    // just use int constants here

    // FIXME MESSAGE, IMAGE(orientation=capture_front,capture_back,binary; representation as bytes+encoding) and NULL should be WireValue top-level types
    public static enum Type {
        UTF8,
        BLOB,
        INT32,
        INT64,
        FLOAT32,
        FLOAT64,
        BOOLEAN,
        MAP,
        LIST,

        // FIXME ID here

        // FIXME
        MESSAGE,
        // FIXME
        IMAGE,
        // FIXME
        NULL

    }





    // FIXME parse
//    public static WireValue of(byte[] bytes, int offset, int n) {
//
//        // expect compressed format here
//
//    }
//





    // FIXME rename to "fromBytes"
    public static WireValue valueOf(ByteBuffer bb) {
        int n = bb.remaining();
        byte[] bytes = new byte[n];
        bb.get(bytes, 0, n);
        return valueOf(bytes, 0);
    }

    // FIXME rename to "fromBytes"
    public static WireValue valueOf(byte[] bytes) {
        return valueOf(bytes, 0);
    }

    static WireValue valueOf(byte[] bytes, int offset) {
        int h = 0xFF & bytes[offset];
        if ((h & H_COMPRESSED) == H_COMPRESSED) {
            int nb = h & ~H_COMPRESSED;
            int size = getint(bytes, offset + 1);
            // next 4 is size in bytes
            int[] offsets = new int[size + 1];
            offsets[0] = offset + 9;
            if (0 < size) {
                for (int i = 1; i <= size; ++i) {
                    offsets[i] = offsets[i - 1] + _byteSize(bytes, offsets[i - 1]);
                }
            }


            CompressionState cs = new CompressionState(bytes, offset, offsets, nb);
            return valueOf(bytes, offsets[size], cs);
        }
        return valueOf(bytes, offset, null);
    }


    static WireValue valueOf(byte[] bytes, int offset, CompressionState cs) {
        int h = 0xFF & bytes[offset];
        if ((h & H_COMPRESSED) == H_COMPRESSED) {
            int luti = h & ~H_COMPRESSED;
            for (int i = 1; i < cs.nb; ++i) {
                luti = (luti << 8) | (0xFF & bytes[offset + i]);
            }
            return valueOf(cs.header, cs.offsets[luti], cs);
        }

        switch (h) {
            case H_UTF8:
                return new CUtf8WireValue(bytes, offset, cs);
            case H_BLOB:
                return new CBlobWireValue(bytes, offset, cs);
            case H_INT32:
                return new CInt32WireValue(bytes, offset, cs);
            case H_INT64:
                return new CInt64WireValue(bytes, offset, cs);
            case H_FLOAT32:
                return new CFloat32WireValue(bytes, offset, cs);
            case H_FLOAT64:
                return new CFloat64WireValue(bytes, offset, cs);
            case H_TRUE_BOOLEAN:
                return new BooleanWireValue(true);
            case H_FALSE_BOOLEAN:
                return new BooleanWireValue(false);
            case H_MAP:
                return new CMapWireValue(bytes, offset, cs);
            case H_LIST:
                return new CListWireValue(bytes, offset, cs);
            case H_INT32_LIST:
//                return new CInt32ListWireValue(bytes, offset, cs);
                // FIXME see listh
                throw new IllegalArgumentException();
            case H_INT64_LIST:
//                return new CInt64ListWireValue(bytes, offset, cs);
                // FIXME see listh
                throw new IllegalArgumentException();
            case H_FLOAT32_LIST:
//                return new CFloat32ListWireValue(bytes, offset, cs);
                // FIXME see listh
                throw new IllegalArgumentException();
            case H_FLOAT64_LIST:
//                return new CFloat64ListWireValue(bytes, offset, cs);
                // FIXME see listh
                throw new IllegalArgumentException();
            case H_NULL:
                return new NullWireValue();
            case H_MESSAGE:
                return new CMessageWireValue(bytes, offset, cs);
            case H_IMAGE:
                return new CImageWireValue(bytes, offset, cs);
            default:
                throw new IllegalArgumentException("" + h);
        }
    }


    // based on byte[] and views into the byte[] (parsing does not expand into a bunch of objects in memory)
    private static abstract class CompressedWireValue extends WireValue {
        byte[] bytes;
        int offset;
        CompressionState cs;


        CompressedWireValue(Type type, byte[] bytes, int offset, CompressionState cs) {
            super(type);
            this.bytes = bytes;
            this.offset = offset;
            this.cs = cs;
        }


    }


    private static class CUtf8WireValue extends CompressedWireValue{
        CUtf8WireValue(byte[] bytes, int offset, CompressionState cs) {
            super(Type.UTF8, bytes, offset, cs);
        }

        @Override
        public String asString() {
            int length = getint(bytes, offset + 1);
            return new String(bytes, offset + 5, length, Charsets.UTF_8);
        }
        @Override
        public int asInt() {
            throw new UnsupportedOperationException();
        }
        @Override
        public long asLong() {
            throw new UnsupportedOperationException();
        }
        @Override
        public float asFloat() {
            throw new UnsupportedOperationException();
        }
        @Override
        public double asDouble() {
            throw new UnsupportedOperationException();
        }
        @Override
        public boolean asBoolean() {
            throw new UnsupportedOperationException();
        }
        @Override
        public List<WireValue> asList() {
            throw new UnsupportedOperationException();
        }
        @Override
        public Map<WireValue, WireValue> asMap() {
            throw new UnsupportedOperationException();
        }
        @Override
        public ByteBuffer asBlob() {
            throw new UnsupportedOperationException();
        }
        @Override
        public Message asMessage() {
            throw new UnsupportedOperationException();
        }
        @Override
        public EncodedImage asImage() {
            throw new UnsupportedOperationException();
        }
    }

    private static class CBlobWireValue extends CompressedWireValue{
        CBlobWireValue(byte[] bytes, int offset, CompressionState cs) {
            super(Type.BLOB, bytes, offset, cs);
        }

        @Override
        public String asString() {
            throw new UnsupportedOperationException();
        }
        @Override
        public int asInt() {
            throw new UnsupportedOperationException();
        }
        @Override
        public long asLong() {
            throw new UnsupportedOperationException();
        }
        @Override
        public float asFloat() {
            throw new UnsupportedOperationException();
        }
        @Override
        public double asDouble() {
            throw new UnsupportedOperationException();
        }
        @Override
        public boolean asBoolean() {
            throw new UnsupportedOperationException();
        }
        @Override
        public List<WireValue> asList() {
            throw new UnsupportedOperationException();
        }
        @Override
        public Map<WireValue, WireValue> asMap() {
            throw new UnsupportedOperationException();
        }
        @Override
        public ByteBuffer asBlob() {
            int length = getint(bytes, offset + 1);
            return ByteBuffer.wrap(bytes, offset + 5, length);
        }
        @Override
        public Message asMessage() {
            throw new UnsupportedOperationException();
        }
        @Override
        public EncodedImage asImage() {
            throw new UnsupportedOperationException();
        }
    }


    private static class CMapWireValue extends CompressedWireValue{
        CMapWireValue(byte[] bytes, int offset, CompressionState cs) {
            super(Type.MAP, bytes, offset, cs);
        }

        @Override
        public String asString() {
            throw new UnsupportedOperationException();
        }
        @Override
        public int asInt() {
            throw new UnsupportedOperationException();
        }
        @Override
        public long asLong() {
            throw new UnsupportedOperationException();
        }
        @Override
        public float asFloat() {
            throw new UnsupportedOperationException();
        }
        @Override
        public double asDouble() {
            throw new UnsupportedOperationException();
        }
        @Override
        public boolean asBoolean() {
            throw new UnsupportedOperationException();
        }
        @Override
        public List<WireValue> asList() {
            throw new UnsupportedOperationException();
        }
        @Override
        public Map<WireValue, WireValue> asMap() {
            Map<WireValue, WireValue> map = new AbstractMap<WireValue, WireValue>() {
                int ds = offset + 5;
                List<WireValue> keys = valueOf(bytes, ds, cs).asList();
                List<WireValue> values = valueOf(bytes, ds + byteSize(bytes, ds, cs), cs).asList();
                int n = keys.size();

                @Override
                public Set<Entry<WireValue, WireValue>> entrySet() {
                    return new AbstractSet<Entry<WireValue, WireValue>>() {
                        @Override
                        public int size() {
                            return n;
                        }
                        @Override
                        public Iterator<Entry<WireValue, WireValue>> iterator() {
                            return new Iterator<Entry<WireValue, WireValue>>() {
                                int i = 0;

                                @Override
                                public boolean hasNext() {
                                    return i < n;
                                }

                                @Override
                                public Entry<WireValue, WireValue> next() {
                                    int j = i++;
                                    return new SimpleImmutableEntry<WireValue, WireValue>(keys.get(j), values.get(j));
                                }

                                @Override
                                public void remove() {
                                    throw new UnsupportedOperationException();
                                }
                            };
                        }
                    };
                }
            };
            return duckMap(map);
        }
        @Override
        public ByteBuffer asBlob() {
            throw new UnsupportedOperationException();
        }
        @Override
        public Message asMessage() {
            throw new UnsupportedOperationException();
        }
        @Override
        public EncodedImage asImage() {
            throw new UnsupportedOperationException();
        }
    }

    private static class CListWireValue extends CompressedWireValue {
        CListWireValue(byte[] bytes, int offset, CompressionState cs) {
            super(Type.LIST, bytes, offset, cs);
        }

        @Override
        public String asString() {
            throw new UnsupportedOperationException();
        }
        @Override
        public int asInt() {
            throw new UnsupportedOperationException();
        }
        @Override
        public long asLong() {
            throw new UnsupportedOperationException();
        }
        @Override
        public float asFloat() {
            throw new UnsupportedOperationException();
        }
        @Override
        public double asDouble() {
            throw new UnsupportedOperationException();
        }
        @Override
        public boolean asBoolean() {
            throw new UnsupportedOperationException();
        }
        @Override
        public List<WireValue> asList() {
            List<WireValue> list = new AbstractList<WireValue>() {
                int n = getint(bytes, offset + 1);
                int[] offsets = null;
                int offseti = 0;

                @Override
                public int size() {
                    return n;
                }

                @Override
                public WireValue get(int index) {
                    if (index < 0 || n <= index) {
                        throw new IndexOutOfBoundsException();
                    }
                    fillOffsets(index);
                    return valueOf(bytes, offsets[index], cs);
                }


                void fillOffsets(int j) {
                    if (offseti <= j) {
                        if (null == offsets) {
                            offsets = new int[n];
                            offsets[0] = offset + 9;
                            offseti = 1;
                        }

                        int i = offseti;
                        for (; i <= j; ++i) {
                            offsets[i] = offsets[i - 1] + byteSize(bytes, offsets[i - 1], cs);
                        }
                        offseti = i;
                    }
                }
            };
            return duckList(list);
        }
        @Override
        public Map<WireValue, WireValue> asMap() {
            throw new UnsupportedOperationException();
        }
        @Override
        public ByteBuffer asBlob() {
            throw new UnsupportedOperationException();
        }
        @Override
        public Message asMessage() {
            throw new UnsupportedOperationException();
        }
        @Override
        public EncodedImage asImage() {
            throw new UnsupportedOperationException();
        }
    }


    private static class CInt32WireValue extends CompressedWireValue {
        CInt32WireValue(byte[] bytes, int offset, CompressionState cs) {
            super(Type.INT32, bytes, offset, cs);
        }

        @Override
        public String asString() {
            return String.valueOf(asInt());
        }
        @Override
        public int asInt() {
            return getint(bytes, offset + 1);
        }
        @Override
        public long asLong() {
            return asInt();
        }
        @Override
        public float asFloat() {
            return (float) asInt();
        }
        @Override
        public double asDouble() {
            return (double) asInt();
        }
        @Override
        public boolean asBoolean() {
            return 0 != asInt();
        }
        @Override
        public List<WireValue> asList() {
            throw new UnsupportedOperationException();
        }
        @Override
        public Map<WireValue, WireValue> asMap() {
            throw new UnsupportedOperationException();
        }
        @Override
        public ByteBuffer asBlob() {
            throw new UnsupportedOperationException();
        }
        @Override
        public Message asMessage() {
            throw new UnsupportedOperationException();
        }
        @Override
        public EncodedImage asImage() {
            throw new UnsupportedOperationException();
        }
    }

    private static class CInt64WireValue extends CompressedWireValue {
        CInt64WireValue(byte[] bytes, int offset, CompressionState cs) {
            super(Type.INT64, bytes, offset, cs);
        }

        @Override
        public String asString() {
            return String.valueOf(asLong());
        }
        @Override
        public int asInt() {
            return (int) asLong();
        }
        @Override
        public long asLong() {
            return getlong(bytes, offset + 1);
        }
        @Override
        public float asFloat() {
            return (float) asLong();
        }
        @Override
        public double asDouble() {
            return (double) asLong();
        }
        @Override
        public boolean asBoolean() {
            return 0 != asLong();
        }
        @Override
        public List<WireValue> asList() {
            throw new UnsupportedOperationException();
        }
        @Override
        public Map<WireValue, WireValue> asMap() {
            throw new UnsupportedOperationException();
        }
        @Override
        public ByteBuffer asBlob() {
            throw new UnsupportedOperationException();
        }
        @Override
        public Message asMessage() {
            throw new UnsupportedOperationException();
        }
        @Override
        public EncodedImage asImage() {
            throw new UnsupportedOperationException();
        }
    }

    private static class CFloat32WireValue extends CompressedWireValue {
        CFloat32WireValue(byte[] bytes, int offset, CompressionState cs) {
            super(Type.FLOAT32, bytes, offset, cs);
        }

        @Override
        public String asString() {
            return String.valueOf(asFloat());
        }
        @Override
        public int asInt() {
            return (int) asFloat();
        }
        @Override
        public long asLong() {
            return (long) asFloat();
        }
        @Override
        public float asFloat() {
            return Float.intBitsToFloat(getint(bytes, offset + 1));
        }
        @Override
        public double asDouble() {
            return (double) asFloat();
        }
        @Override
        public boolean asBoolean() {
            return 0.f != asFloat();
        }
        @Override
        public List<WireValue> asList() {
            throw new UnsupportedOperationException();
        }
        @Override
        public Map<WireValue, WireValue> asMap() {
            throw new UnsupportedOperationException();
        }
        @Override
        public ByteBuffer asBlob() {
            throw new UnsupportedOperationException();
        }
        @Override
        public Message asMessage() {
            throw new UnsupportedOperationException();
        }
        @Override
        public EncodedImage asImage() {
            throw new UnsupportedOperationException();
        }
    }

    private static class CFloat64WireValue extends CompressedWireValue {
        CFloat64WireValue(byte[] bytes, int offset, CompressionState cs) {
            super(Type.FLOAT64, bytes, offset, cs);
        }

        @Override
        public String asString() {
            return String.valueOf(asDouble());
        }
        @Override
        public int asInt() {
            return (int) asDouble();
        }
        @Override
        public long asLong() {
            return (long) asDouble();
        }
        @Override
        public float asFloat() {
            return (float) asDouble();
        }
        @Override
        public double asDouble() {
            return Double.longBitsToDouble(getlong(bytes, offset + 1));
        }
        @Override
        public boolean asBoolean() {
            return 0.0 != asDouble();
        }
        @Override
        public List<WireValue> asList() {
            throw new UnsupportedOperationException();
        }
        @Override
        public Map<WireValue, WireValue> asMap() {
            throw new UnsupportedOperationException();
        }
        @Override
        public ByteBuffer asBlob() {
            throw new UnsupportedOperationException();
        }
        @Override
        public Message asMessage() {
            throw new UnsupportedOperationException();
        }
        @Override
        public EncodedImage asImage() {
            throw new UnsupportedOperationException();
        }
    }

    private static class CMessageWireValue extends CompressedWireValue {
        CMessageWireValue(byte[] bytes, int offset, CompressionState cs) {
            super(Type.MESSAGE, bytes, offset, cs);
        }

        @Override
        public String asString() {
            throw new UnsupportedOperationException();
        }
        @Override
        public int asInt() {
            throw new UnsupportedOperationException();
        }
        @Override
        public long asLong() {
            throw new UnsupportedOperationException();
        }
        @Override
        public float asFloat() {
            throw new UnsupportedOperationException();
        }
        @Override
        public double asDouble() {
            throw new UnsupportedOperationException();
        }
        @Override
        public boolean asBoolean() {
            throw new UnsupportedOperationException();
        }
        @Override
        public List<WireValue> asList() {
            throw new UnsupportedOperationException();
        }
        @Override
        public Map<WireValue, WireValue> asMap() {
            throw new UnsupportedOperationException();
        }
        @Override
        public ByteBuffer asBlob() {
            throw new UnsupportedOperationException();
        }
        @Override
        public Message asMessage() {
            // [0] is the header
            return MessageCodec.valueOf(bytes, offset + 1, cs);
        }
        @Override
        public EncodedImage asImage() {
            throw new UnsupportedOperationException();
        }
    }

    private static class CImageWireValue extends CompressedWireValue {
        CImageWireValue(byte[] bytes, int offset, CompressionState cs) {
            super(Type.IMAGE, bytes, offset, cs);
        }

        @Override
        public String asString() {
            throw new UnsupportedOperationException();
        }
        @Override
        public int asInt() {
            throw new UnsupportedOperationException();
        }
        @Override
        public long asLong() {
            throw new UnsupportedOperationException();
        }
        @Override
        public float asFloat() {
            throw new UnsupportedOperationException();
        }
        @Override
        public double asDouble() {
            throw new UnsupportedOperationException();
        }
        @Override
        public boolean asBoolean() {
            throw new UnsupportedOperationException();
        }
        @Override
        public List<WireValue> asList() {
            throw new UnsupportedOperationException();
        }
        @Override
        public Map<WireValue, WireValue> asMap() {
            throw new UnsupportedOperationException();
        }
        @Override
        public ByteBuffer asBlob() {
            throw new UnsupportedOperationException();
        }
        @Override
        public Message asMessage() {
            throw new UnsupportedOperationException();
        }
        @Override
        public EncodedImage asImage() {
            // [0] is the header
            return ImageCodec.valueOf(bytes, offset + 1);
        }
    }




    // LUT sizes: 2^7, 2^15
    // index maps to byte[]
    private static class CompressionState {
        byte[] header;
        int offset;
        int[] offsets;
        int nb;

        CompressionState(byte[] header, int offset, int[] offsets, int nb) {
            this.header = header;
            this.offset = offset;
            this.offsets = offsets;
            this.nb = nb;
        }
    }






    public static WireValue of(JsonElement e) {
        if (e.isJsonPrimitive()) {
            JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isBoolean()) {
                return of(p.getAsBoolean());
            } else if (p.isNumber()) {
                try {
                    long n = p.getAsLong();
                    if ((int) n == n) {
                        return of((int) n);
                    } else {
                        return of(n);
                    }
                } catch (NumberFormatException x) {
                    double d = p.getAsDouble();
                    if ((float) d == d) {
                        return of((float) d);
                    } else {
                        return of(d);
                    }
                }
            } else if (p.isString()) {
                return of(p.getAsString());
            } else {
                throw new IllegalArgumentException();
            }
        } else if (e.isJsonObject()) {
            JsonObject object = e.getAsJsonObject();
            Map<WireValue, WireValue> m = new HashMap<WireValue, WireValue>(4);
            for (Map.Entry<String, JsonElement> oe : object.entrySet()) {
                m.put(of(oe.getKey()), of(oe.getValue()));
            }
            return of(m);
        } else if (e.isJsonArray()) {
            JsonArray array = e.getAsJsonArray();
            List<WireValue> list = new ArrayList<WireValue>(4);
            for (JsonElement ae : array) {
                list.add(of(ae));
            }
            return of(list);
        } else {
            throw new IllegalArgumentException();
        }
    }


    public static WireValue valueOfJson(String json) {
        try {
            return valueOfJson(new StringReader(json));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** @param jsonIn json source. Closed by this method. */
    public static WireValue valueOfJson(Reader jsonIn) throws IOException {
        JsonReader r = new JsonReader(jsonIn);
        try {
            return parseJson(r);
        } finally {
            r.close();
        }
    }

    private static WireValue parseJson(JsonReader r) throws IOException {
        switch (r.peek()) {
            case BEGIN_OBJECT: {
                Map<WireValue, WireValue> map = new HashMap<WireValue, WireValue>(4);
                r.beginObject();
                while (!JsonToken.END_OBJECT.equals(r.peek())) {
                    WireValue key = WireValue.of(r.nextName());
                    WireValue value = parseJson(r);
                    map.put(key, value);
                }
                r.endObject();
                return WireValue.of(map);
            }
            case BEGIN_ARRAY: {
                List<WireValue> list = new ArrayList<WireValue>(4);
                r.beginArray();
                while (!JsonToken.END_ARRAY.equals(r.peek())) {
                    WireValue value = parseJson(r);
                    list.add(value);
                }
                r.endArray();
                return WireValue.of(list);
            }
            case STRING:
                return WireValue.of(r.nextString());
            case NUMBER: {
                try {
                    long n = r.nextLong();
                    if ((int) n == n) {
                        return WireValue.of((int) n);
                    } else {
                        return WireValue.of(n);
                    }
                } catch (NumberFormatException e) {
                    double d = r.nextDouble();
                    if ((float) d == d) {
                        return WireValue.of((float) d);
                    } else {
                        return WireValue.of(d);
                    }
                }
            }
            case BOOLEAN:
                return WireValue.of(r.nextBoolean());
            case NULL:
                // FIXME have a NULL WireValue Type
                throw new IllegalArgumentException();
            default:
            case END_DOCUMENT:
                throw new IllegalArgumentException();
        }
    }




    public static WireValue of(@Nullable Object value) {
        if (null == value) {
            return of();
        }
        if (value instanceof WireValue) {
            return (WireValue) value;
        }
        if (value instanceof CharSequence) {
            return of(value.toString());
        }
        if (value instanceof Number) {
            if (value instanceof Integer) {
                return of(((Integer) value).intValue());
            }
            if (value instanceof Long) {
                return of(((Long) value).longValue());
            }
            if (value instanceof Float) {
                return of(((Float) value).floatValue());
            }
            if (value instanceof Double) {
                return of(((Double) value).doubleValue());
            }
            // default int32
            return of(((Number) value).intValue());
        }
        if (value instanceof Boolean) {
            return of(((Boolean) value).booleanValue());
        }
        if (value instanceof byte[]) {
            return of((byte[]) value);
        }
        // FIXME bytebuffer
        if (value instanceof Map) {
            return of(asWireValueMap((Map<?, ?>) value));
        }
        if (value instanceof Collection) {
            if (value instanceof List) {
                return of(asWireValueList((List<?>) value));
            }
            return of(asWireValueList((Collection<?>) value));
        }
        if (value instanceof Message) {
            return of((Message) value);
        }
        if (value instanceof EncodedImage) {
            return of((EncodedImage) value);
        }
        if (value instanceof JsonElement) {
            return of((JsonElement) value);
        }
        // FIXME ID
        if (value instanceof Id) {
            return of(value.toString());
        }
        throw new IllegalArgumentException();
    }

    static WireValue of(byte[] value) {
        return of(value, 0, value.length);
    }
    static WireValue of(byte[] value, int offset, int length) {
        return new BlobWireValue(value, offset, length);
    }

    // FIXME of(ByteBuffer)

    static WireValue of(String value) {
        return new Utf8WireValue(value);
    }

    static WireValue of(int value) {
        return new NumberWireValue(value);
    }

    static WireValue of(long value) {
        return new NumberWireValue(value);
    }

    static WireValue of(float value) {
        return new NumberWireValue(value);
    }

    static WireValue of(double value) {
        return new NumberWireValue(value);
    }

    static WireValue of(boolean value) {
        return new BooleanWireValue(value);
    }

    static WireValue of(Map<WireValue, WireValue> m) {
        return new MapWireValue(m);
    }

    static WireValue of(List<WireValue> list) {
        return new ListWireValue(list);
    }


    static WireValue of(Message m) {
        return new MessageWireValue(m);
    }

    static WireValue of(EncodedImage image) {
        return new ImageWireValue(image);
    }

    // null
    static WireValue of() {
        return new NullWireValue();
    }






    final Type type;
    boolean hashCodeSet = false;
    int hashCode;


    WireValue(Type type) {
        this.type = type;
    }


    // FIXME as* functions that represent the wirevalue as something



    public final Type getType() {
        return type;
    }


    @Override
    public final int hashCode() {
        if (!hashCodeSet) {
            hashCode = _hashCode(this);
            hashCodeSet = true;
        }
        return hashCode;
    }
    static int _hashCode(WireValue value) {
        switch (value.type) {
            case UTF8:
                return value.asString().hashCode();
            case BLOB:
                return value.asBlob().hashCode();
            case INT32:
                return value.asInt();
            case INT64:
                long n = value.asLong();
                return (int) (n ^ (n >>> 32));
            case FLOAT32:
                return Float.floatToIntBits(value.asFloat());
            case FLOAT64:
                long dn = Double.doubleToLongBits(value.asDouble());
                return (int) (dn ^ (dn >>> 32));
            case BOOLEAN:
                return value.asBoolean() ? 1231 : 1237;
            case MAP: {
                Map<WireValue, WireValue> map = value.asMap();
//                List<WireValue> keys = stableKeys(map);
//                List<WireValue> values = stableValues(map, keys);
                // important: order should not affect hash code
                int c = 0;
                for (Map.Entry<WireValue, WireValue> e : map.entrySet()) {
                    c += _hashCode(e.getKey());
                    c += _hashCode(e.getValue());
                }
                return c;
             }
            case LIST: {
                int c = 0;
                for (WireValue v : value.asList()) {
                    c = 31 * c + _hashCode(v);
                }
                return c;
            }
            case MESSAGE:
                return value.asMessage().hashCode();
            case IMAGE:
                return value.asImage().hashCode();
            case NULL:
                return 0;
            default:
                throw new IllegalArgumentException("" + value.type);
        }
    }

    @Override
    public final boolean equals(Object obj) {
        if (!(obj instanceof WireValue)) {
            return false;
        }
        WireValue b = (WireValue) obj;
        return _equals(this, b);
    }
    static boolean _equals(WireValue a, WireValue b) {
        if (!a.type.equals(b.type) || a.hashCode() != b.hashCode()) {
            return false;
        }
        switch (a.type) {
            case UTF8:
                return a.asString().equals(b.asString());
            case BLOB:
                return a.asBlob().equals(b.asBlob());
            case INT32:
                return a.asInt() == b.asInt();
            case INT64:
                return a.asLong() == b.asLong();
            case FLOAT32:
                return a.asFloat() == b.asFloat();
            case FLOAT64:
                return a.asDouble() == b.asDouble();
            case BOOLEAN:
                return a.asBoolean() == b.asBoolean();
            case MAP:
                return a.asMap().equals(b.asMap());
            case LIST:
                return a.asList().equals(b.asList());
            case MESSAGE:
                return a.asMessage().equals(b.asMessage());
            case IMAGE:
                return a.asImage().equals(b.asImage());
            case NULL:
                return true;
            default:
                throw new IllegalArgumentException();
        }
    }


    @Override
    public String toString() {
        return toText();
    }

    public String toDebugString() {
        return String.format("%s %s", type, toString());
    }


    public String toText() {
        switch (type) {
            case UTF8:
                return asString();
            case BLOB:
                return base64(asBlob());
            case INT32:
                return String.valueOf(asInt());
            case INT64:
                return String.valueOf(asLong());
            case FLOAT32:
                return String.valueOf(asFloat());
            case FLOAT64:
                return String.valueOf(asDouble());
            case BOOLEAN:
                return String.valueOf(asBoolean());
            case MAP:
                return toJson();
            case LIST:
                return toJson();
            case MESSAGE:
                // TODO
                return "[message]";
            case IMAGE:
                return base64(asImage().toBuffer());
            case NULL:
                // TODO
                return "";
            default:
                throw new IllegalArgumentException();
        }
    }



    public String toJson() {
        // TODO better size upper bound - will the better memory efficiency be worth a pre-scan?
        StringWriter sw = new StringWriter();
        try {
            toJson(sw);
            return sw.toString();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public void toJson(Writer jsonOut) throws IOException {
        JsonWriter w = new JsonWriter(jsonOut);
        try {
            toJson(this, w);
        } finally {
            w.close();
        }
    }


    static void toJson(WireValue value, JsonWriter w) throws IOException {
        switch (value.getType()) {
            case UTF8:
                w.value(value.asString());
                break;
            case BLOB:
                w.value(base64(value.asBlob()));
                break;
            case INT32:
                w.value(value.asInt());
                break;
            case INT64:
                w.value(value.asLong());
                break;
            case FLOAT32:
                w.value(value.asFloat());
                break;
            case FLOAT64:
                w.value(value.asDouble());
                break;
            case BOOLEAN:
                w.value(value.asBoolean());
                break;
            case MAP:
                w.beginObject();
                Map<WireValue, WireValue> map = value.asMap();
                for (WireValue key : stableKeys(map)) {
                    w.name(key.toText());
                    toJson(map.get(key), w);
                }
                w.endObject();
                break;
            case LIST:
                w.beginArray();
                for (WireValue v : value.asList()) {
                    toJson(v, w);
                }
                w.endArray();
                break;
            case MESSAGE:
                // FIXME 0.1.1 write as object
                // TODO base64(asImage().toBuffer());
                w.value("[message]");
                break;
            case IMAGE:
                // FIXME 0.1.1 write as object
                w.value("[image]");
                break;
            case NULL:
                w.nullValue();
                break;
            default:
                throw new IllegalArgumentException();
        }
    }

    static String base64(ByteBuffer bytes) {
        byte[] b64;
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(bytes.remaining() * 4 / 3);
            Base64OutputStream b64os = new Base64OutputStream(baos, true, 0, null);

            WritableByteChannel channel = Channels.newChannel(b64os);
            channel.write(bytes);
            channel.close();

            b64 = baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return new String(b64, Charsets.UTF_8);
    }




    static final int H_COMPRESSED = 0x80;
    static final int H_UTF8 = 1;
    static final int H_BLOB = 2;
    static final int H_INT32 = 3;
    static final int H_INT64 = 4;
    static final int H_FLOAT32 = 5;
    static final int H_FLOAT64 = 6;
    static final int H_TRUE_BOOLEAN = 7;
    static final int H_FALSE_BOOLEAN = 8;
    static final int H_MAP = 9;
    static final int H_LIST = 10;
    static final int H_INT32_LIST = 11;
    static final int H_INT64_LIST = 12;
    static final int H_FLOAT32_LIST = 13;
    static final int H_FLOAT64_LIST = 14;
    static final int H_NULL = 15;
    static final int H_MESSAGE = 16;
    static final int H_IMAGE = 17;


    static byte[] header(int nb, int v) {
        switch (nb) {
            case 1:
                return new byte[]{(byte) (v & 0xFF)};
            case 2:
                return new byte[]{(byte) ((v >>> 8) & 0xFF), (byte) (v & 0xFF)};
            case 3:
                return new byte[]{(byte) ((v >>> 16) & 0xFF), (byte) ((v >>> 8) & 0xFF), (byte) (v & 0xFF)};
            default:
                throw new IllegalArgumentException();
        }
    }

    static int listh(List<WireValue> list) {
        return H_LIST;
        // FIXME implement later
//        int n = list.size();
//        if (0 == n) {
//            return H_LIST;
//        }
//        Type t = list.get(0).getType();
//        for (int i = 0; i < n; ++i) {
//            if (!t.equals(list.get(1).getType())) {
//                return H_LIST;
//            }
//        }
//        switch (t) {
//            case INT32:
//                return H_INT32;
//            case INT64:
//                return H_INT64_LIST;
//            case FLOAT32:
//                return H_FLOAT32_LIST;
//            case FLOAT64:
//                return H_FLOAT64_LIST;
//            default:
//                return H_LIST;
//        }
    }




    // toByte should always compress
    static int byteSize(byte[] bytes, int offset, CompressionState cs) {
        int h = 0xFF & bytes[offset];
        if ((h & H_COMPRESSED) == H_COMPRESSED) {
            return cs.nb;
        }
        return _byteSize(bytes, offset);
    }

    static int _byteSize(byte[] bytes, int offset) {
        int h = 0xFF & bytes[offset];
//        System.out.printf("_byteSize %s\n", h);
        switch (h) {
            case H_UTF8:
                return 5 + getint(bytes, offset + 1);
            case H_BLOB:
                return 5 + getint(bytes, offset + 1);
            case H_INT32:
                return 5;
            case H_INT64:
                return 9;
            case H_FLOAT32:
                return 5;
            case H_FLOAT64:
                return 9;
            case H_TRUE_BOOLEAN:
                return 1;
            case H_FALSE_BOOLEAN:
                return 1;
            case H_MAP:
                return 5 + getint(bytes, offset + 1);
            case H_LIST:
                return 9 + getint(bytes, offset + 5);
            case H_INT32_LIST:
                // FIXME see listh
                throw new IllegalArgumentException();
            case H_INT64_LIST:
                // FIXME see listh
                throw new IllegalArgumentException();
            case H_FLOAT32_LIST:
                // FIXME see listh
                throw new IllegalArgumentException();
            case H_FLOAT64_LIST:
                // FIXME see listh
                throw new IllegalArgumentException();
            case H_MESSAGE:
                return 5 + getint(bytes, offset + 1);
            case H_IMAGE:
                return 5 + getint(bytes, offset + 1);
            case H_NULL:
                return 1;
            default:
                throw new IllegalArgumentException("" + h);
        }
    }



    public void toBytes(ByteBuffer bb) {

        Lb lb = new Lb();
        lb.init(this);
        if (lb.opt()) {
            // write the lut header
            bb.put((byte) (H_COMPRESSED | lb.lutNb));
            bb.putInt(lb.lut.size());
            bb.putInt(0);
            int i = bb.position();

            for (Lb.S s : lb.lut) {
                _toBytes(s.value, lb, bb);
            }

            int bytes = bb.position() - i;
            bb.putInt(i - 4, bytes);
//            System.out.printf("header %d bytes\n", bytes);
        }

        int i = bb.position();
        toBytes(this, lb, bb);
        int bytes = bb.position() - i;
//        System.out.printf("body %d bytes\n", bytes);

    }
    private static void toBytes(WireValue value, Lb lb, ByteBuffer bb) {
        int luti = lb.luti(value);
        if (0 <= luti) {
            byte[] header = header(lb.lutNb, luti);
            header[0] |= H_COMPRESSED;
            bb.put(header);
        } else {
            _toBytes(value, lb, bb);
        }
    }
    private static void _toBytes(WireValue value, Lb lb, ByteBuffer bb) {
        switch (value.getType()) {
            case MAP: {
                bb.put((byte) H_MAP);
                bb.putInt(0);

                int i = bb.position();

                Map<WireValue, WireValue> m = value.asMap();
                List<WireValue> keys = stableKeys(m);
                toBytes(of(keys), lb, bb);
                List<WireValue> values = stableValues(m, keys);
                toBytes(of(values), lb, bb);

                int bytes = bb.position() - i;
                bb.putInt(i - 4, bytes);

                break;
            }
            case LIST: {
                List<WireValue> list = value.asList();
                int listh = listh(list);
                if (H_LIST == listh) {
                    bb.put((byte) H_LIST);
                    bb.putInt(list.size());
                    bb.putInt(0);

                    int i = bb.position();

                    for (WireValue v : value.asList()) {
                        toBytes(v, lb, bb);
                    }

                    int bytes = bb.position() - i;
                    bb.putInt(i - 4, bytes);
                } else {
                    // primitive homogeneous list
                    bb.put((byte) listh);
                    bb.putInt(list.size());
                    bb.putInt(0);

                    int i = bb.position();

                    switch (listh) {
                        case H_INT32_LIST:
                            for (WireValue v : value.asList()) {
                                bb.putInt(v.asInt());
                            }
                            break;
                        case H_INT64_LIST:
                            for (WireValue v : value.asList()) {
                                bb.putLong(v.asLong());
                            }
                            break;
                        case H_FLOAT32_LIST:
                            for (WireValue v : value.asList()) {
                                bb.putFloat(v.asFloat());
                            }
                            break;
                        case H_FLOAT64_LIST:
                            for (WireValue v : value.asList()) {
                                bb.putDouble(v.asDouble());
                            }
                            break;
                    }

                    int bytes = bb.position() - i;
                    bb.putInt(i - 4, bytes);

                }
                break;
            }
            case BLOB: {
                ByteBuffer b = value.asBlob();

                bb.put((byte) H_BLOB);
                bb.putInt(b.remaining());
                bb.put(b);

                break;
            }
            case UTF8: {
                byte[] b = value.asString().getBytes(Charsets.UTF_8);

                bb.put((byte) H_UTF8);
                bb.putInt(b.length);
                bb.put(b);

                break;
            }
            case INT32:
                bb.put((byte) H_INT32);
                bb.putInt(value.asInt());
                break;
            case INT64:
                bb.put((byte) H_INT64);
                bb.putLong(value.asLong());
                break;
            case FLOAT32:
                bb.put((byte) H_FLOAT32);
                bb.putFloat(value.asFloat());
                break;
            case FLOAT64:
                bb.put((byte) H_FLOAT64);
                bb.putDouble(value.asDouble());
                break;
            case BOOLEAN:
                bb.put((byte) (value.asBoolean() ? H_TRUE_BOOLEAN : H_FALSE_BOOLEAN));
                break;
            case MESSAGE:
                bb.put((byte) H_MESSAGE);
                MessageCodec.toBytes(value.asMessage(), lb, bb);
                break;
            case IMAGE:
                bb.put((byte) H_IMAGE);
                ImageCodec.toBytes(value.asImage(), lb, bb);
                break;
            case NULL:
                bb.put((byte) H_NULL);
                break;
            default:
                throw new IllegalArgumentException();
        }
    }


    // lut builder
    static class Lb {
        static class S {
            WireValue value;

            int count;

            // FIXME record this in expandOne/expand
            int maxd = -1;
            int maxi = -1;
//            boolean r = false;

            int luti = -1;
        }


        Map<WireValue, S> stats = new HashMap<WireValue, S>(4);
//        List<S> rs = new ArrayList<S>(4);

        List<S> lut = new ArrayList<S>(4);
        int lutNb = 0;


        int luti(WireValue value) {
            S s = stats.get(value);
            if (null != s) {
                return s.luti;
            } else {
                // not in the lut
                return -1;
            }
        }


        void init(WireValue value) {
            expand(value, 0, 0);
        }

        int expand(WireValue value, int d, int i) {
            switch (value.getType()) {
                case MAP:
                    expandOne(value, d, i);
                    i += 1;
                    // the rest
                    Map<WireValue, WireValue> m = value.asMap();
                    List<WireValue> keys = stableKeys(m);
                    i = expand(of(keys), d + 1, i);
                    List<WireValue> values = stableValues(m, keys);
                    i = expand(of(values), d + 1, i);
                    break;
                case LIST:
                    expandOne(value, d, i);
                    i += 1;
                    // the rest
                    for (WireValue v : value.asList()) {
                        i = expand(v, d + 1, i);
                    }
                    break;
                default:
                    expandOne(value, d, i);
                    i += 1;
                    break;
            }
            return i;
        }
        void expandOne(WireValue value, int d, int i) {
            S s = stats.get(value);
            if (null == s) {
                s = new S();
                s.value = value;
                stats.put(value, s);
            }
            s.count += 1;
            s.maxd = Math.max(s.maxd, d);
            s.maxi = Math.max(s.maxi, i);
//            if (rmask && !s.r) {
//                s.r = true;
//                rs.add(s);
//            }
        }

        boolean opt() {

            int nb = estimateLutNb();
            if (nb <= 0) {
                return false;
            }

            // order r values by maxd
            // if include each, subtract down
            List<S> rs = new ArrayList<S>(stats.values());
            Collections.sort(rs, new Comparator<S>() {
                @Override
                public int compare(S a, S b) {
                    if (a == b) {
                        return 0;
                    }

                    // maxd
                    if (a.maxd < b.maxd) {
                        return -1;
                    }
                    if (b.maxd < a.maxd) {
                        return 1;
                    }

                    // maxi
                    if (a.maxi < b.maxi) {
                        return -1;
                    }
                    if (b.maxi < a.maxi) {
                        return 1;
                    }

                    return 0;
                }
            });
            for (S s : rs) {
                if (include(s, nb)) {
                    s.luti = lut.size();
                    lut.add(s);
                    collapse(s.value);
                }
            }

            lutNb = nb(lut.size());

            // lut is ordered implicity by maxd

            return true;
        }

        void collapse(WireValue value) {
            switch (value.getType()) {
                case MAP:
                    collapseOne(value);
                    // the rest
                    Map<WireValue, WireValue> m = value.asMap();
                    List<WireValue> keys = stableKeys(m);
                    collapse(of(keys));
                    List<WireValue> values = stableValues(m, keys);
                    collapse(of(values));
                    break;
                case LIST:
                    collapseOne(value);
                    // the rest
                    for (WireValue v : value.asList()) {
                        collapse(v);
                    }
                    break;
                default:
                    collapseOne(value);
                    break;
            }
        }
        void collapseOne(WireValue value) {
            S s = stats.get(value);
            assert null != s;
            s.count -= 1;
        }





        int[] sizes = new int[]{0, 0x10, 0x0100, 0x010000, 0x01000000};
        int nb(int c) {
            int nb = 0;
            while (sizes[nb] < c) {
                ++nb;
            }
            return nb;
        }






        int estimateLutNb() {
            int nb = nb(count(1));
            if (nb == sizes.length) {
                // too many unique values; punt
                return -1;
            }
            int pnb;
            do {
                pnb = nb;
                nb = nb(count(nb));
            } while (nb < pnb);

            return pnb;
        }
        int count(int nb) {
            int c = 0;
            for (S s : stats.values()) {
                if (include(s, nb)) {
                    c += 1;
                }
            }
            return c;
        }


        boolean include(S s, int nb) {
            int b = quickLowerSize(s.value.getType());
            return nb * s.count + b < s.count * b;
        }

        int quickLowerSize(Type type) {
            switch (type) {
                case MAP:
                    // TODO this is a lower estimate
                    // length + ...
                    return 12;
                case LIST:
                    // TODO this is a lower estimate
                    return 8;
                case BLOB:
                    // TODO this is a lower estimate
                    return 8;
                case UTF8:
                    // TODO this is a lower estimate
                    return 8;
                case INT32:
                    return 4;
                case INT64:
                    return 8;
                case FLOAT32:
                    return 4;
                case FLOAT64:
                    return 8;
                case BOOLEAN:
                    return 1;
                case MESSAGE:
                    // FIXME
                    return 1;
                case IMAGE:
                    // FIXME
                    return 1;
                case NULL:
                    return 1;
                default:
                    throw new IllegalArgumentException();
            }
        }
    }

    static List<WireValue> stableKeys(Map<WireValue, WireValue> m) {
        List<WireValue> keys = new ArrayList<WireValue>(m.keySet());
        Collections.sort(keys, COMPARATOR_STABLE);
        return keys;
    }

    static List<WireValue> stableValues(Map<WireValue, WireValue> m, List<WireValue> keys) {
        List<WireValue> values = new ArrayList<WireValue>(keys.size());
        for (WireValue key : keys) {
            values.add(m.get(key));
        }
        return values;
    }


    static final Comparator<WireValue> COMPARATOR_STABLE = new StableComparator();

    // stable ordering for all values
    static final class StableComparator implements Comparator<WireValue> {
        @Override
        public int compare(WireValue a, WireValue b) {
            Type ta = a.getType();
            Type tb = b.getType();

            int d = ta.ordinal() - tb.ordinal();
            if (0 != d) {
                return d;
            }
            assert ta.equals(tb);

            switch (ta) {
                case MAP: {
                    Map<WireValue, WireValue> amap = a.asMap();
                    Map<WireValue, WireValue> bmap = b.asMap();
                    d = amap.size() - bmap.size();
                    if (0 != d) {
                        return d;
                    }
                    List<WireValue> akeys = stableKeys(amap);
                    List<WireValue> bkeys = stableKeys(bmap);
                    d = compare(of(akeys), of(bkeys));
                    if (0 != d) {
                        return d;
                    }
                    return compare(of(stableValues(amap, akeys)), of(stableValues(bmap, bkeys)));
                }
                case LIST: {
                    List<WireValue> alist = a.asList();
                    List<WireValue> blist = b.asList();
                    int n = alist.size();
                    int m = blist.size();
                    d = n - m;
                    if (0 != d) {
                        return d;
                    }
                    for (int i = 0; i < n; ++i) {
                        d = compare(alist.get(i), blist.get(i));
                        if (0 != d) {
                            return d;
                        }
                    }
                    return 0;
                }
                case BLOB: {
                    ByteBuffer abytes = a.asBlob();
                    ByteBuffer bbytes = b.asBlob();
                    int n = abytes.remaining();
                    int m = bbytes.remaining();
                    d = n - m;
                    if (0 != d) {
                        return d;
                    }
                    for (int i = 0; i < n; ++i) {
                        d = (0xFF & abytes.get(i)) - (0xFF & bbytes.get(i));
                        if (0 != d) {
                            return d;
                        }
                    }
                    return 0;
                }
                case UTF8:
                    return a.asString().compareTo(b.asString());
                case INT32:
                    int ai = a.asInt();
                    int bi = b.asInt();
                    if (ai < bi) {
                        return -1;
                    }
                    if (bi < ai) {
                        return 1;
                    }
                    return 0;
                case INT64:
                    long an = a.asLong();
                    long bn = b.asLong();
                    if (an < bn) {
                        return -1;
                    }
                    if (bn < an) {
                        return 1;
                    }
                    return 0;
                case FLOAT32:
                    return Float.compare(a.asFloat(), b.asFloat());
                case FLOAT64:
                    return Double.compare(a.asDouble(), b.asDouble());
                case MESSAGE:
                    return a.asMessage().id.compareTo(b.asMessage().id);
                case IMAGE: {
                    // compare blobs
                    ByteBuffer abytes = a.asImage().toBuffer();
                    ByteBuffer bbytes = b.asImage().toBuffer();
                    int n = abytes.remaining();
                    int m = bbytes.remaining();
                    d = n - m;
                    if (0 != d) {
                        return d;
                    }
                    for (int i = 0; i < n; ++i) {
                        d = (0xFF & abytes.get(i)) - (0xFF & bbytes.get(i));
                        if (0 != d) {
                            return d;
                        }
                    }
                    return 0;
                }
                case NULL:
                    return 0;
                default:
                    throw new IllegalArgumentException();
            }
        }
    }


//    static final class SizeEstimate {
//        long bytes;
//        int verifiedNodes;
//        int unverifiedNodes;
//    }
//
//    public abstract SizeEstimate estimateSize();


    // logical view, regardless of wire format

    // FIXME move Id to first class type
    public Id asId() {
        return Id.valueOf(asString());
    }

    public abstract String asString();
    public abstract int asInt();
    public abstract long asLong();
    public abstract float asFloat();
    public abstract double asDouble();
    public abstract boolean asBoolean();
    public abstract List<WireValue> asList();
    public abstract Map<WireValue, WireValue> asMap();
    public abstract ByteBuffer asBlob();
    public abstract Message asMessage();
    public abstract EncodedImage asImage();








    private static class BlobWireValue extends WireValue {
        final byte[] value;
        final int offset;
        final int length;

        BlobWireValue(byte[] value, int offset, int length) {
            super(Type.BLOB);
            this.value = value;
            this.offset = offset;
            this.length = length;
        }

        @Override
        public String asString() {
            return base64(ByteBuffer.wrap(value, offset, length));
        }
        @Override
        public int asInt() {
            throw new UnsupportedOperationException();
        }
        @Override
        public long asLong() {
            throw new UnsupportedOperationException();
        }
        @Override
        public float asFloat() {
            throw new UnsupportedOperationException();
        }
        @Override
        public double asDouble() {
            throw new UnsupportedOperationException();
        }
        @Override
        public boolean asBoolean() {
            throw new UnsupportedOperationException();
        }
        @Override
        public List<WireValue> asList() {
            throw new UnsupportedOperationException();
        }
        @Override
        public Map<WireValue, WireValue> asMap() {
            throw new UnsupportedOperationException();
        }
        @Override
        public ByteBuffer asBlob() {
            return ByteBuffer.wrap(value, offset, length);
        }
        @Override
        public Message asMessage() {
            throw new UnsupportedOperationException();
        }
        @Override
        public EncodedImage asImage() {
            throw new UnsupportedOperationException();
        }


    }

    private static class Utf8WireValue extends WireValue {
        final String value;

        Utf8WireValue(String value) {
            super(Type.UTF8);
            this.value = value;
        }

        @Override
        public String asString() {
            return value;
        }
        @Override
        public int asInt() {
            return Integer.parseInt(value);
        }
        @Override
        public long asLong() {
            return Long.parseLong(value);
        }
        @Override
        public float asFloat() {
            return Float.parseFloat(value);
        }
        @Override
        public double asDouble() {
            return Double.parseDouble(value);
        }
        @Override
        public boolean asBoolean() {
            return Boolean.parseBoolean(value);
        }
        @Override
        public List<WireValue> asList() {
            throw new UnsupportedOperationException();
        }
        @Override
        public Map<WireValue, WireValue> asMap() {
            throw new UnsupportedOperationException();
        }
        @Override
        public ByteBuffer asBlob() {
            return ByteBuffer.wrap(value.getBytes(Charsets.UTF_8));
        }
        @Override
        public Message asMessage() {
            throw new UnsupportedOperationException();
        }
        @Override
        public EncodedImage asImage() {
            throw new UnsupportedOperationException();
        }
    }

    private static class NumberWireValue extends WireValue {
        static Type type(Number n) {
            if (n instanceof Integer) {
                return Type.INT32;
            }
            if (n instanceof Long) {
                return Type.INT64;
            }
            if (n instanceof Float) {
                return Type.FLOAT32;
            }
            if (n instanceof Double) {
                return Type.FLOAT64;
            }
            throw new IllegalArgumentException();
        }

        final Number value;

        NumberWireValue(Number value) {
            super(type(value));
            this.value = value;
        }

        @Override
        public String asString() {
            return value.toString();
        }
        @Override
        public int asInt() {
            return value.intValue();
        }
        @Override
        public long asLong() {
            return value.longValue();
        }
        @Override
        public float asFloat() {
            return value.floatValue();
        }
        @Override
        public double asDouble() {
            return value.doubleValue();
        }
        @Override
        public boolean asBoolean() {
            // c style
            return 0 != value.floatValue();
        }
        @Override
        public List<WireValue> asList() {
            throw new UnsupportedOperationException();
        }
        @Override
        public Map<WireValue, WireValue> asMap() {
            throw new UnsupportedOperationException();
        }
        @Override
        public ByteBuffer asBlob() {
            // TODO
            throw new UnsupportedOperationException();
        }
        @Override
        public Message asMessage() {
            throw new UnsupportedOperationException();
        }
        @Override
        public EncodedImage asImage() {
            throw new UnsupportedOperationException();
        }
    }

    private static class BooleanWireValue extends WireValue {

        final boolean value;

        BooleanWireValue(boolean value) {
            super(Type.BOOLEAN);
            this.value = value;
        }

        @Override
        public String asString() {
            return String.valueOf(value);
        }
        @Override
        public int asInt() {
            return value ? 1 : 0;
        }
        @Override
        public long asLong() {
            return value ? 1L : 0L;
        }
        @Override
        public float asFloat() {
            return value ? 1.f : 0.f;
        }
        @Override
        public double asDouble() {
            return value ? 1.0 : 0.0;
        }
        @Override
        public boolean asBoolean() {
            return value;
        }
        @Override
        public List<WireValue> asList() {
            throw new UnsupportedOperationException();
        }
        @Override
        public Map<WireValue, WireValue> asMap() {
            throw new UnsupportedOperationException();
        }
        @Override
        public ByteBuffer asBlob() {
            // TODO
            throw new UnsupportedOperationException();
        }
        @Override
        public Message asMessage() {
            throw new UnsupportedOperationException();
        }
        @Override
        public EncodedImage asImage() {
            throw new UnsupportedOperationException();
        }
    }

    private static class ListWireValue extends WireValue {
        final List<WireValue> value;

        ListWireValue(List<WireValue> value) {
            super(Type.LIST);
            this.value = duckList(value);
        }

        @Override
        public String asString() {
            return value.toString();
        }
        @Override
        public int asInt() {
            throw new UnsupportedOperationException();
        }
        @Override
        public long asLong() {
            throw new UnsupportedOperationException();
        }
        @Override
        public float asFloat() {
            throw new UnsupportedOperationException();
        }
        @Override
        public double asDouble() {
            throw new UnsupportedOperationException();
        }
        @Override
        public boolean asBoolean() {
            throw new UnsupportedOperationException();
        }
        @Override
        public List<WireValue> asList() {
            return value;
        }
        @Override
        public Map<WireValue, WireValue> asMap() {
            throw new UnsupportedOperationException();
        }
        @Override
        public ByteBuffer asBlob() {
            // TODO
            throw new UnsupportedOperationException();
        }
        @Override
        public Message asMessage() {
            throw new UnsupportedOperationException();
        }
        @Override
        public EncodedImage asImage() {
            throw new UnsupportedOperationException();
        }
    }

    private static class MapWireValue extends WireValue {
        final Map<WireValue, WireValue> value;

        MapWireValue(Map<WireValue, WireValue> value) {
            super(Type.MAP);
            this.value = duckMap(value);
        }

        @Override
        public String asString() {
            return value.toString();
        }
        @Override
        public int asInt() {
            throw new UnsupportedOperationException();
        }
        @Override
        public long asLong() {
            throw new UnsupportedOperationException();
        }
        @Override
        public float asFloat() {
            throw new UnsupportedOperationException();
        }
        @Override
        public double asDouble() {
            throw new UnsupportedOperationException();
        }
        @Override
        public boolean asBoolean() {
            throw new UnsupportedOperationException();
        }
        @Override
        public List<WireValue> asList() {
            throw new UnsupportedOperationException();
        }
        @Override
        public Map<WireValue, WireValue> asMap() {
            return value;
        }
        @Override
        public ByteBuffer asBlob() {
            // TODO
            throw new UnsupportedOperationException();
        }
        @Override
        public Message asMessage() {
            throw new UnsupportedOperationException();
        }
        @Override
        public EncodedImage asImage() {
            throw new UnsupportedOperationException();
        }
    }

    private static class MessageWireValue extends WireValue {
        final Message message;

        MessageWireValue(Message message) {
            super(Type.MESSAGE);
            this.message = message;
        }

        @Override
        public String asString() {
            throw new UnsupportedOperationException();
        }
        @Override
        public int asInt() {
            throw new UnsupportedOperationException();
        }
        @Override
        public long asLong() {
            throw new UnsupportedOperationException();
        }
        @Override
        public float asFloat() {
            throw new UnsupportedOperationException();
        }
        @Override
        public double asDouble() {
            throw new UnsupportedOperationException();
        }
        @Override
        public boolean asBoolean() {
            throw new UnsupportedOperationException();
        }
        @Override
        public List<WireValue> asList() {
            throw new UnsupportedOperationException();
        }
        @Override
        public Map<WireValue, WireValue> asMap() {
            throw new UnsupportedOperationException();
        }
        @Override
        public ByteBuffer asBlob() {
            throw new UnsupportedOperationException();
        }
        @Override
        public Message asMessage() {
            return message;
        }
        @Override
        public EncodedImage asImage() {
            throw new UnsupportedOperationException();
        }
    }

    private static class ImageWireValue extends WireValue {
        final EncodedImage image;

        ImageWireValue(EncodedImage image) {
            super(Type.IMAGE);
            this.image = image;
        }


        @Override
        public String asString() {
            throw new UnsupportedOperationException();
        }
        @Override
        public int asInt() {
            throw new UnsupportedOperationException();
        }
        @Override
        public long asLong() {
            throw new UnsupportedOperationException();
        }
        @Override
        public float asFloat() {
            throw new UnsupportedOperationException();
        }
        @Override
        public double asDouble() {
            throw new UnsupportedOperationException();
        }
        @Override
        public boolean asBoolean() {
            throw new UnsupportedOperationException();
        }
        @Override
        public List<WireValue> asList() {
            throw new UnsupportedOperationException();
        }
        @Override
        public Map<WireValue, WireValue> asMap() {
            throw new UnsupportedOperationException();
        }
        @Override
        public ByteBuffer asBlob() {
            throw new UnsupportedOperationException();
        }
        @Override
        public Message asMessage() {
            throw new UnsupportedOperationException();
        }
        @Override
        public EncodedImage asImage() {
            return image;
        }
    }

    private static class NullWireValue extends WireValue {
        NullWireValue() {
            super(Type.NULL);
        }


        @Override
        public String asString() {
            throw new UnsupportedOperationException();
        }
        @Override
        public int asInt() {
            throw new UnsupportedOperationException();
        }
        @Override
        public long asLong() {
            throw new UnsupportedOperationException();
        }
        @Override
        public float asFloat() {
            throw new UnsupportedOperationException();
        }
        @Override
        public double asDouble() {
            throw new UnsupportedOperationException();
        }
        @Override
        public boolean asBoolean() {
            throw new UnsupportedOperationException();
        }
        @Override
        public List<WireValue> asList() {
            throw new UnsupportedOperationException();
        }
        @Override
        public Map<WireValue, WireValue> asMap() {
            throw new UnsupportedOperationException();
        }
        @Override
        public ByteBuffer asBlob() {
            throw new UnsupportedOperationException();
        }
        @Override
        public Message asMessage() {
            throw new UnsupportedOperationException();
        }
        @Override
        public EncodedImage asImage() {
            throw new UnsupportedOperationException();
        }
    }



    private static final class IdCodec {
        static final int LENGTH = Id.LENGTH;

        public static Id valueOf(byte[] bytes, int offset) {
            return new Id(bytes, offset);
        }

        public static void toBytes(Id id, ByteBuffer bb) {
            bb.put(id.bytes, id.offset, Id.LENGTH);
        }
    }


    private static final class ImageCodec {
        static final int H_F_WEBP = 1;
        static final int H_F_JPEG = 2;
        static final int H_F_PNG = 3;

        static final int H_O_REAR_FACING = 1;
        static final int H_O_FRONT_FACING = 2;


        public static EncodedImage valueOf(byte[] bytes, int offset) {
            EncodedImage.Format format;
            int c = offset;
            // skip bytes
            c += 4;
            switch (0xFF & bytes[c]) {
                case H_F_WEBP:
                    format = EncodedImage.Format.WEBP;
                    break;
                case H_F_JPEG:
                    format = EncodedImage.Format.JPEG;
                    break;
                case H_F_PNG:
                    format = EncodedImage.Format.PNG;
                    break;
                default:
                    throw new IllegalArgumentException();
            }
            c += 1;
            EncodedImage.Orientation orientation;
            switch (0xFF & bytes[c]) {
                case H_O_REAR_FACING:
                    orientation = EncodedImage.Orientation.REAR_FACING;
                    break;
                case H_O_FRONT_FACING:
                    orientation = EncodedImage.Orientation.FRONT_FACING;
                    break;
                default:
                    throw new IllegalArgumentException();
            }
            c += 1;
            int width = getint(bytes, c);
            c += 4;
            int height = getint(bytes, c);
            c += 4;
            int length = getint(bytes, c);
            c += 4;
            return new EncodedImage(format, orientation, width, height, bytes, c, length);
        }

        public static void toBytes(EncodedImage image, Lb lb, ByteBuffer bb) {
            bb.putInt(0);
            int i = bb.position();

            switch (image.format) {
                case WEBP:
                    bb.put((byte) H_F_WEBP);
                    break;
                case JPEG:
                    bb.put((byte) H_F_JPEG);
                    break;
                case PNG:
                    bb.put((byte) H_F_PNG);
                    break;
                default:
                    throw new IllegalArgumentException();
            }
            switch (image.orientation) {
                case REAR_FACING:
                    bb.put((byte) H_O_REAR_FACING);
                    break;
                case FRONT_FACING:
                    bb.put((byte) H_O_FRONT_FACING);
                    break;
                default:
                    throw new IllegalArgumentException();
            }
            bb.putInt(image.width);
            bb.putInt(image.height);
            bb.putInt(image.length);
            bb.put(image.bytes, image.offset, image.length);

            int bytes = bb.position() - i;
            bb.putInt(i - 4, bytes);
        }
    }

    private static final class MessageCodec {


        public static Message valueOf(byte[] bytes, int offset, CompressionState cs) {
            int c = offset;
            // skip bytes
            c += 4;

            Id id = IdCodec.valueOf(bytes, c);
            c += IdCodec.LENGTH;
            Id groupId = IdCodec.valueOf(bytes, c);
            c += IdCodec.LENGTH;
            int groupPriority = getint(bytes, c);
            c += 4;
            Route route = Route.valueOf(WireValue.valueOf(bytes, c, cs).asString());
            c += 5 + getint(bytes, c + 1);
            Map<WireValue, WireValue> headers = WireValue.valueOf(bytes, c, cs).asMap();
            c += 5 + getint(bytes, c + 1);
            Map<WireValue, WireValue> parameters = WireValue.valueOf(bytes, c, cs).asMap();
            return new Message(id, groupId, groupPriority, route, headers, parameters);
        }

        public static void toBytes(Message message, Lb lb, ByteBuffer bb) {
            bb.putInt(0);
            int i = bb.position();

            IdCodec.toBytes(message.id, bb);
            IdCodec.toBytes(message.groupId, bb);
            bb.putInt(message.groupPriority);
            // TODO (?) Route codec to avoid string conversion
            WireValue._toBytes(WireValue.of(message.route.toString()), lb, bb);
            WireValue._toBytes(WireValue.of(message.headers), lb, bb);
            WireValue._toBytes(WireValue.of(message.parameters), lb, bb);

            int bytes = bb.position() - i;
            bb.putInt(i - 4, bytes);
        }
    }


    public static boolean isWireValues(Collection<?> values) {
        for (Object value : values) {
            if (!(value instanceof WireValue)) {
                return false;
            }
        }
        return true;
    }


    private static Map<WireValue, WireValue> asWireValueMap(Map<?, ?> map) {
        if (isWireValues(map.keySet())) {
            // lazily transform the values
            return Maps.transformValues((Map<WireValue, Object>) map, new Function<Object, WireValue>() {
                @Override
                public WireValue apply(@Nullable Object input) {
                    return of(input);
                }
            });
        } else {
            Map<WireValue, WireValue> t = new HashMap<WireValue, WireValue>(map.size());
            for (Map.Entry<?, ?> e : map.entrySet()) {
                @Nullable Object j = t.put(of(e.getKey()), of(e.getValue()));
                if (null != j) {
                    throw new IllegalStateException("Map to WireValue is not a bijection.");
                }
            }
            return t;
        }
    }
    private static List<WireValue> asWireValueList(List<?> list) {
        return Lists.transform(list, new Function<Object, WireValue>() {
            @Override
            public WireValue apply(@Nullable Object input) {
                return of(input);
            }
        });
    }
    private static List<WireValue> asWireValueList(Collection<?> collection) {
        List<WireValue> wireValueList = new ArrayList<WireValue>(collection.size());
        for (Object value : collection) {
            wireValueList.add(of(value));
        }
        return wireValueList;
    }
    private static Collection<WireValue> asWireValueCollection(Collection<?> collection) {
        return Collections2.transform(collection, new Function<Object, WireValue>() {
            @Override
            public WireValue apply(@Nullable Object input) {
                return of(input);
            }
        });
    }


    // FIXME config
    private static final boolean WV_MAP_STRICT_TYPES = false;

    private static WireValue duckCoerce(@Nullable Object arg) {
        WireValue value = WireValue.of(arg);
        if (value != arg && WV_MAP_STRICT_TYPES) {
            throw new IllegalArgumentException();
        }
        return value;
    }


    /** coerces arguments to {@link WireValue} where the {@link Map} interfaces
     * uses an object type (which won't trigger compile errors).
     * While not good practice to not use {@link WireValue} arguments,
     * the intention is clearly to use them and the program should work. */
    private static Map<WireValue, WireValue> duckMap(final Map<WireValue, WireValue> map) {
        return new ForwardingMap<WireValue, WireValue>() {
            @Override
            protected Map<WireValue, WireValue> delegate() {
                return map;
            }


            @Override
            public WireValue get(@Nullable Object key) {
                return super.get(duckCoerce(key));
            }

            @Override
            public WireValue remove(Object object) {
                return super.remove(duckCoerce(object));
            }

            @Override
            public boolean containsKey(@Nullable Object key) {
                return super.containsKey(duckCoerce(key));
            }

            @Override
            public boolean containsValue(@Nullable Object value) {
                return super.containsValue(duckCoerce(value));
            }
        };
    }

    private static List<WireValue> duckList(final List<WireValue> list) {
        return new ForwardingList<WireValue>() {
            @Override
            protected List<WireValue> delegate() {
                return list;
            }

            @Override
            public boolean remove(Object object) {
                return super.remove(duckCoerce(object));
            }

            @Override
            public boolean removeAll(Collection<?> collection) {
                return super.removeAll(asWireValueCollection(collection));
            }

            @Override
            public boolean contains(Object object) {
                return super.contains(duckCoerce(object));
            }

            @Override
            public boolean containsAll(Collection<?> collection) {
                return super.containsAll(asWireValueCollection(collection));
            }

            @Override
            public int indexOf(Object element) {
                return super.indexOf(duckCoerce(element));
            }

            @Override
            public int lastIndexOf(Object element) {
                return super.lastIndexOf(duckCoerce(element));
            }
        };
    }



    // wire utils

    public static int getint(byte[] bytes, int offset) {
        return ((0xFF & bytes[offset]) << 24)
                | ((0xFF & bytes[offset + 1]) << 16)
                | ((0xFF & bytes[offset + 2]) << 8)
                | (0xFF & bytes[offset + 3]);
    }
    public static long getlong(byte[] bytes, int offset) {
        return ((0xFFL & bytes[offset]) << 56)
                | ((0xFFL & bytes[offset + 1]) << 48)
                | ((0xFFL & bytes[offset + 2]) << 40)
                | ((0xFFL & bytes[offset + 3]) << 32)
                | ((0xFFL & bytes[offset + 4]) << 24)
                | ((0xFFL & bytes[offset + 5]) << 16)
                | ((0xFFL & bytes[offset + 6]) << 8)
                | (0xFFL & bytes[offset + 7]);
    }

    public static void putint(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value >>> 24);
        bytes[offset + 1] = (byte) (value >>> 16);
        bytes[offset + 2] = (byte) (value >>> 8);
        bytes[offset + 3] = (byte) value;
    }
    public static void putlong(byte[] bytes, int offset, long value) {
        bytes[offset] = (byte) (value >>> 56);
        bytes[offset + 1] = (byte) (value >>> 48);
        bytes[offset + 2] = (byte) (value >>> 40);
        bytes[offset + 3] = (byte) (value >>> 32);
        bytes[offset + 4] = (byte) (value >>> 24);
        bytes[offset + 5] = (byte) (value >>> 16);
        bytes[offset + 6] = (byte) (value >>> 8);
        bytes[offset + 7] = (byte) value;
    }




}
