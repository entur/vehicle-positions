package org.entur.vehicles.service.planned;

import org.entur.vehicles.service.snapshot.IdCodec;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Drives the codecs of records that refer to no other section. */
final class CodecFixtures {

    private CodecFixtures() {
    }

    interface Interner<R> {
        void intern(IdCodec.Writer ids, R record);
    }

    interface Writer<R> {
        void write(DataOutputStream out, IdCodec.Writer ids, R record) throws IOException;
    }

    interface Reader<R> {
        R read(DataInputStream in, IdCodec.Reader ids) throws IOException;
    }

    /** Interns, writes and reads back one record through the codec's own methods. */
    static <R> R roundTrip(R record, Interner<R> interner, Writer<R> writer, Reader<R> reader) throws IOException {
        IdCodec.Writer ids = new IdCodec.Writer();
        interner.intern(ids, record);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            ids.writeTable(out);
            writer.write(out, ids, record);
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            IdCodec.Reader readIds = new IdCodec.Reader();
            readIds.readTable(in);
            R read = reader.read(in, readIds);
            assertThat(in.available()).as("bytes left after the record").isZero();
            return read;
        }
    }

    interface RefWriter<R> {
        void write(DataOutputStream out, IdCodec.Writer ids, R record, SectionIndex.Writer first, SectionIndex.Writer second) throws IOException;
    }

    interface RefReader<R> {
        R read(DataInputStream in, IdCodec.Reader ids, SectionIndex.Reader first, SectionIndex.Reader second) throws IOException;
    }

    /** What a round trip read back, and how many bytes the record itself took. */
    record Written<R>(R read, int recordBytes) {
    }

    /**
     * Interns, writes and reads back one record that refers into two earlier sections holding
     * the given ids, in the order the codec takes them.
     */
    static <R> Written<R> roundTrip(R record, Interner<R> interner, RefWriter<R> writer, RefReader<R> reader,
                                    List<String> first, List<String> second) throws IOException {
        IdCodec.Writer ids = new IdCodec.Writer();
        first.forEach(ids::intern);
        second.forEach(ids::intern);
        interner.intern(ids, record);
        SectionIndex.Writer firstIndex = new SectionIndex.Writer();
        first.forEach(firstIndex::add);
        SectionIndex.Writer secondIndex = new SectionIndex.Writer();
        second.forEach(secondIndex::add);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int recordStart;
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            ids.writeTable(out);
            out.flush();
            recordStart = bytes.size();
            writer.write(out, ids, record, firstIndex, secondIndex);
        }
        int recordBytes = bytes.size() - recordStart;

        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            IdCodec.Reader readIds = new IdCodec.Reader();
            readIds.readTable(in);
            SectionIndex.Reader firstSection = new SectionIndex.Reader(first.size());
            first.forEach(firstSection::add);
            SectionIndex.Reader secondSection = new SectionIndex.Reader(second.size());
            second.forEach(secondSection::add);
            R read = reader.read(in, readIds, firstSection, secondSection);
            assertThat(in.available()).as("bytes left after the record").isZero();
            return new Written<>(read, recordBytes);
        }
    }

    /** A reader positioned on the document's root START_ELEMENT. */
    static XMLStreamReader at(String xml) throws Exception {
        XMLStreamReader r = XMLInputFactory.newFactory().createXMLStreamReader(new StringReader(xml));
        r.nextTag();
        return r;
    }
}
