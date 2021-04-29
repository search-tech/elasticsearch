/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */
package org.elasticsearch.transport;

import org.elasticsearch.Version;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.bytes.CompositeBytesReference;
import org.elasticsearch.common.compress.CompressorFactory;
import org.elasticsearch.common.io.Streams;
import org.elasticsearch.common.io.stream.BytesStreamOutput;
import org.elasticsearch.common.io.stream.OutputStreamStreamOutput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.common.util.concurrent.ThreadContext;

import java.io.IOException;
import java.util.Set;

abstract class OutboundMessage extends NetworkMessage {

    protected final Writeable message;

    OutboundMessage(ThreadContext threadContext, Version version, byte status, long requestId, Writeable message) {
        super(threadContext, version, status, requestId);
        this.message = message;
    }

    BytesReference serialize(BytesStreamOutput bytesStream) throws IOException {
        bytesStream.setVersion(version);
        bytesStream.skip(TcpHeader.headerSize(version));

        // The compressible bytes stream will not close the underlying bytes stream
        BytesReference reference;
        int variableHeaderLength = -1;
        final long preHeaderPosition = bytesStream.position();

        if (version.onOrAfter(TcpHeader.VERSION_WITH_HEADER_SIZE)) {
            writeVariableHeader(bytesStream);
            variableHeaderLength = Math.toIntExact(bytesStream.position() - preHeaderPosition);
        }

        final boolean compress = TransportStatus.isCompress(status);
        final StreamOutput stream = compress ? wrapCompressed(bytesStream) : bytesStream;
        final BytesReference zeroCopyBuffer;
        try {
            stream.setVersion(version);
            stream.setFeatures(bytesStream.getFeatures());

            if (variableHeaderLength == -1) {
                writeVariableHeader(stream);
            }
            if (message instanceof BytesTransportRequest) {
                BytesTransportRequest bRequest = (BytesTransportRequest) message;
                bRequest.writeThin(stream);
                zeroCopyBuffer = bRequest.bytes;
            } else if (message instanceof RemoteTransportException) {
                stream.writeException((RemoteTransportException) message);
                zeroCopyBuffer = BytesArray.EMPTY;
            } else {
                message.writeTo(stream);
                zeroCopyBuffer = BytesArray.EMPTY;
            }
        } finally {
            // We have to close here before accessing the bytes when using compression to ensure that some marker bytes (EOS marker)
            // are written.
            if (compress) {
                stream.close();
            }
        }
        final BytesReference message = bytesStream.bytes();
        if (zeroCopyBuffer.length() == 0) {
            reference = message;
        } else {
            reference = CompositeBytesReference.of(message, zeroCopyBuffer);
        }

        bytesStream.seek(0);
        final int contentSize = reference.length() - TcpHeader.headerSize(version);
        TcpHeader.writeHeader(bytesStream, requestId, status, version, contentSize, variableHeaderLength);
        return reference;
    }

    // compressed stream wrapped bytes must be no-close wrapped since we need to close the compressed wrapper below to release
    // resources and write EOS marker bytes but must not yet release the bytes themselves
    private OutputStreamStreamOutput wrapCompressed(BytesStreamOutput bytesStream) throws IOException {
        return new OutputStreamStreamOutput(CompressorFactory.COMPRESSOR.threadLocalOutputStream(Streams.noCloseStream(bytesStream)));
    }

    protected void writeVariableHeader(StreamOutput stream) throws IOException {
        threadContext.writeTo(stream);
    }

    static class Request extends OutboundMessage {

        private final String[] features;
        private final String action;

        Request(ThreadContext threadContext, String[] features, Writeable message, Version version, String action, long requestId,
                boolean isHandshake, boolean compress) {
            super(threadContext, version, setStatus(compress, isHandshake, message), requestId, message);
            this.features = features;
            this.action = action;
        }

        @Override
        protected void writeVariableHeader(StreamOutput stream) throws IOException {
            super.writeVariableHeader(stream);
            if (version.onOrAfter(Version.V_6_3_0)) {
                stream.writeStringArray(features);
            }
            stream.writeString(action);
        }

        private static byte setStatus(boolean compress, boolean isHandshake, Writeable message) {
            byte status = 0;
            status = TransportStatus.setRequest(status);
            if (compress && OutboundMessage.canCompress(message)) {
                status = TransportStatus.setCompress(status);
            }
            if (isHandshake) {
                status = TransportStatus.setHandshake(status);
            }

            return status;
        }


        @Override
        public String toString() {
            return "Request{" + action + "}{" + requestId + "}{" + isError() + "}{" + isCompress() + "}{" + isHandshake() + "}";
        }
    }

    static class Response extends OutboundMessage {

        private final Set<String> features;

        Response(ThreadContext threadContext, Set<String> features, Writeable message, Version version, long requestId,
                 boolean isHandshake, boolean compress) {
            super(threadContext, version, setStatus(compress, isHandshake, message), requestId, message);
            this.features = features;
        }

        @Override
        protected void writeVariableHeader(StreamOutput stream) throws IOException {
            super.writeVariableHeader(stream);
            stream.setFeatures(features);
        }

        private static byte setStatus(boolean compress, boolean isHandshake, Writeable message) {
            byte status = 0;
            status = TransportStatus.setResponse(status);
            if (message instanceof RemoteTransportException) {
                status = TransportStatus.setError(status);
            }
            if (compress) {
                status = TransportStatus.setCompress(status);
            }
            if (isHandshake) {
                status = TransportStatus.setHandshake(status);
            }

            return status;
        }

        @Override
        public String toString() {
            return "Response{" + requestId + "}{" + isError() + "}{" + isCompress() + "}{" + isHandshake() + "}{"
                    + message.getClass() + "}";
        }
    }

    private static boolean canCompress(Writeable message) {
        return message instanceof BytesTransportRequest == false;
    }
}
