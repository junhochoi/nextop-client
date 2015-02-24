package io.nextop.client.http;

import io.nextop.Message;
import io.nextop.Route;
import io.nextop.client.*;
import io.nextop.client.retry.SendStrategy;
import io.nextop.org.apache.http.*;
import io.nextop.org.apache.http.client.HttpRequestRetryHandler;
import io.nextop.org.apache.http.client.config.RequestConfig;
import io.nextop.org.apache.http.client.methods.*;
import io.nextop.org.apache.http.client.protocol.HttpClientContext;
import io.nextop.org.apache.http.client.protocol.RequestClientConnControl;
import io.nextop.org.apache.http.client.utils.URIUtils;
import io.nextop.org.apache.http.config.ConnectionConfig;
import io.nextop.org.apache.http.config.MessageConstraints;
import io.nextop.org.apache.http.conn.*;
import io.nextop.org.apache.http.conn.HttpConnectionFactory;
import io.nextop.org.apache.http.conn.routing.HttpRoute;
import io.nextop.org.apache.http.entity.ContentLengthStrategy;
import io.nextop.org.apache.http.impl.DefaultConnectionReuseStrategy;
import io.nextop.org.apache.http.impl.client.DefaultConnectionKeepAliveStrategy;
import io.nextop.org.apache.http.impl.conn.ConnectionShutdownException;
import io.nextop.org.apache.http.impl.conn.DefaultHttpResponseParserFactory;
import io.nextop.org.apache.http.impl.conn.DefaultManagedHttpClientConnection;
import io.nextop.org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import io.nextop.org.apache.http.impl.entity.LaxContentLengthStrategy;
import io.nextop.org.apache.http.impl.entity.StrictContentLengthStrategy;
import io.nextop.org.apache.http.impl.execchain.*;
import io.nextop.org.apache.http.impl.io.DefaultHttpRequestWriterFactory;
import io.nextop.org.apache.http.io.HttpMessageParserFactory;
import io.nextop.org.apache.http.io.HttpMessageWriterFactory;
import io.nextop.org.apache.http.io.SessionInputBuffer;
import io.nextop.org.apache.http.io.SessionOutputBuffer;
import io.nextop.org.apache.http.protocol.*;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;


public final class HttpNode extends AbstractMessageControlNode {


    // FIXME pull version from build
    private static final String DEFAULT_USER_AGENT = "Nextop/0.1.3";


    /** emit progress every 1KiB by default */
    private static final int DEFAULT_EMIT_Q_BYTES = 1024;



    private final int maxConcurrentConnections = 2;


    PoolingHttpClientConnectionManager clientConnectionManager =
            new PoolingHttpClientConnectionManager(new NextopHttpClientConnectionFactory());



    // this is the strategy for one entry before possibly yielding
    // each time the entry is taken, the strategy is run from the beginning
    // this is a really aggressive strategy that relies on CONNECTIVITY STATUS
    // to stop retrying on a bad connection
    private SendStrategy sendStrategy = new SendStrategy.Builder()
            .init(0, TimeUnit.MILLISECONDS)
            .withUniformRandom(2000, TimeUnit.MILLISECONDS)
            .repeat(2)
            .build();

    volatile boolean active = true;

    private List<Thread> looperThreads = Collections.emptyList();


    @Nullable
    private Wire.Factory wireAdapterFactory = null;


    public HttpNode() {
    }


    @Override
    public void onActive(boolean active, MessageControlMetrics metrics) {
        this.active = active;

        if (!active) {
            for (Thread t : looperThreads) {
                t.interrupt();
            }

            // FIXME on false, upstream.onTransfer(mcs)
        }
    }

    @Override
    public void onTransfer(MessageControlState mcs) {
        super.onTransfer(mcs);

        // FIXME bind mcs to the loopers - ultimately remove the onTransfer callback and just pass the mcs in init
        if (active) {
            // note that the mcs coordinates between multiple loopers
            int n = maxConcurrentConnections;
            Thread[] threads = new Thread[n];
            for (int i = 0; i < n; ++i) {
                threads[i] = new RequestLooper();
            }
            looperThreads = Arrays.asList(threads);
            for (int i = 0; i < n; ++i) {
                threads[i].start();
            }
        }
    }

    @Override
    public void onMessageControl(MessageControl mc) {
        assert MessageControl.Direction.SEND.equals(mc.dir);
        if (active && !mcs.onActiveMessageControl(mc, upstream)) {
            switch (mc.type) {
                case MESSAGE:
                    mcs.add(mc.message);
                    break;
                default:
                    // ignore
                    break;
            }
        }
    }





    private final class RequestLooper extends Thread {
        @Nullable
        ProgressCallback progressCallback = null;

        @Nullable
        Wire.Adapter wireAdapter = null;
        @Nullable
        Wire.Factory _wireAdapterFactory = null;


        @Override
        public void run() {
            while (active) {
                @Nullable MessageControlState.Entry entry;
                try {
                    entry = mcs.takeFirstAvailable(HttpNode.this,
                            Integer.MAX_VALUE, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    continue;
                }

                if (null != entry) {
                    assert null == entry.end;
                    try {
                        // set up or switch the wire adapter
                        if (null != wireAdapterFactory && (null == wireAdapter
                                || _wireAdapterFactory != wireAdapterFactory)) {
                            _wireAdapterFactory = wireAdapterFactory;
                            wireAdapter = wireAdapterFactory.createAdapter();
                        }

                        end(entry, execute(entry));
                    } catch (IOException e) {
                        handleTransportException(entry, e);
                    } catch (HttpException e) {
                        handleTransportException(entry, e);
                    } catch (Throwable t) {
                        // FIXME remove
//                        t.printStackTrace();

                        // an internal issue
                        // can never recover from this (assume the system is deterministic)
                        end(entry, MessageControlState.End.ERROR);
                    }
                }
            }
        }
        /** factored out exception handling in place of multi-catch */
        private void handleTransportException(MessageControlState.Entry entry, Exception e) {
            // FIXME remove
//            e.printStackTrace();

            if (null == entry.end) {
                // FIXME for HttpException the endpoint not speaking the protocol correctly
                // FIXME the retry should not be as aggressive in this case

                // at this point the entry was elected to yield
                // in this case, check whether the message has indicated it can be moved to the end of the line
                if (Message.isYieldable(entry.message)) {
                    mcs.yield(entry.id);
                }
                mcs.release(entry.id, HttpNode.this);
            } // else already removed
        }
        private void end(final MessageControlState.Entry entry, MessageControlState.End end) {
            mcs.remove(entry.id, end);

            final Route route = entry.message.inboxRoute();
            switch (end) {
                case COMPLETED:
                    post(new Runnable() {
                        @Override
                        public void run() {
                            upstream.onMessageControl(MessageControl.receive(MessageControl.Type.COMPLETE, route));
                        }
                    });
                    break;
                case ERROR:
                case CANCELED:
                    post(new Runnable() {
                        @Override
                        public void run() {
                            upstream.onMessageControl(MessageControl.receive(MessageControl.Type.ERROR, route));
                        }
                    });
                    break;
                default:
                    throw new IllegalStateException();
            }
        }


        private MessageControlState.End execute(final MessageControlState.Entry entry) throws IOException, HttpException {
            final HttpRequest request;
            try {
                request = Message.toHttpRequest(entry.message);
            } catch (URISyntaxException e) {
                // can never send this
                return MessageControlState.End.ERROR;
            }

            final HttpHost target;
            try {
                target = Message.toHttpHost(entry.message);
            } catch (URISyntaxException e) {
                // can never send this
                return MessageControlState.End.ERROR;
            }

            final Message responseMessage;
            progressCallback = new ProgressAdapter(entry);
            try {
                HttpResponse response = doExecute(createExecChain(entry),
                        target, request, null);

                responseMessage = Message.fromHttpResponse(response).setRoute(entry.message.inboxRoute()).build();
            } finally {
                progressCallback = null;
            }

            post(new Runnable() {
                @Override
                public void run() {
                    upstream.onMessageControl(MessageControl.receive(responseMessage));
                }
            });
            return MessageControlState.End.COMPLETED;
        }


        /** lifted version of {@link io.nextop.org.apache.http.impl.client.CloseableHttpClient#doExecute} */
        private CloseableHttpResponse doExecute(
                ClientExecChain execChain,
                HttpHost target,
                HttpRequest request,
                @Nullable HttpContext context) throws IOException, HttpException {
            HttpExecutionAware execAware = null;
            if (request instanceof HttpExecutionAware) {
                execAware = (HttpExecutionAware) request;
            }
            final HttpRequestWrapper wrapper = HttpRequestWrapper.wrap(request);
            final HttpClientContext localcontext = HttpClientContext.adapt(
                    null != context ? context : new BasicHttpContext());
            final HttpRoute route = new HttpRoute(target);
            RequestConfig config = null;
            if (request instanceof Configurable) {
                config = ((Configurable) request).getConfig();
            }
            if (config != null) {
                localcontext.setRequestConfig(config);
            }
            return execChain.execute(route, wrapper, localcontext, execAware);
        }


        private ClientExecChain createExecChain(MessageControlState.Entry entry) {
            NextopClientExec nextopExec = new NextopClientExec(
                    new NextopHttpRequestExecutor(progressCallback),
                    clientConnectionManager,
                    DefaultConnectionReuseStrategy.INSTANCE,
                    DefaultConnectionKeepAliveStrategy.INSTANCE
            );
            return new RetryExec(nextopExec, new NextopHttpRequestRetryHandler(sendStrategy, entry, mcs));
        }

    }



    final class ProgressAdapter implements ProgressCallback {
        final MessageControlState.Entry entry;


        ProgressAdapter(MessageControlState.Entry entry) {
            this.entry = entry;
        }


        @Override
        public void onSendStarted(int tryCount) {
            post(new Runnable() {
                @Override
                public void run() {
                    mcs.setOutboxTransferProgress(entry.id,
                            MessageControlState.TransferProgress.none(entry.id));
                }
            });
        }

        @Override
        public void onSendProgress(final long sentBytes, final long sendTotalBytes) {
            post(new Runnable() {
                @Override
                public void run() {
                    mcs.setOutboxTransferProgress(entry.id,
                            MessageControlState.TransferProgress.create(entry.id, sentBytes, sendTotalBytes));
                }
            });
        }

        @Override
        public void onSendCompleted(final long sentBytes, final long sendTotalBytes) {
            post(new Runnable() {
                @Override
                public void run() {
                    mcs.setOutboxTransferProgress(entry.id,
                            MessageControlState.TransferProgress.create(entry.id, sentBytes, sendTotalBytes));
                }
            });
        }

        @Override
        public void onReceiveStarted(int tryCount) {
            post(new Runnable() {
                @Override
                public void run() {
                    mcs.setInboxTransferProgress(entry.id,
                            MessageControlState.TransferProgress.none(entry.id));
                }
            });
        }

        @Override
        public void onReceiveProgress(final long receivedBytes, final long receiveTotalBytes) {
            post(new Runnable() {
                @Override
                public void run() {
                    mcs.setInboxTransferProgress(entry.id,
                            MessageControlState.TransferProgress.create(entry.id, receivedBytes, receiveTotalBytes));
                }
            });
        }

        @Override
        public void onReceiveCompleted(final long receivedBytes, final long receiveTotalBytes) {
            post(new Runnable() {
                @Override
                public void run() {
                    mcs.setInboxTransferProgress(entry.id,
                            MessageControlState.TransferProgress.create(entry.id, receivedBytes, receiveTotalBytes));
                }
            });
        }
    }


    /** can be called from any thread. Expect the IO thread to call. */
    static interface ProgressCallback {
        void onSendStarted(int tryCount);
        void onSendProgress(long sentBytes, long sendTotalBytes);
        void onSendCompleted(long sentBytes, long sendTotalBytes);

        void onReceiveStarted(int tryCount);
        void onReceiveProgress(long receivedBytes, long receiveTotalBytes);
        void onReceiveCompleted(long receivedBytes, long receiveTotalBytes);
    }


    // PoolingHttpClientConnectionManager
    // -- uses ManagedHttpClientConnectionFactory
    //    -- uses LoggingManagedHttpClientConnection    #getOutputStream(socket)  #getInputStream(Socket)
    //       -- implements ManagedHttpClientConnection     #bind(Socket)
    //       -- extends DefaultManagedHttpClientConnection   WANT TO USE THIS
    // DefaultConnectionReuseStrategy

    // FIXME wrap in a retry exec  with NextopHttpRequestRetryHandler
    // FIXME    use the message property idempotent to influence retry also
    // DefaultHttpRequestRetryHandler



    // FIXME create Nextop exec chain per request
    // FIXME



    // NextopRetryExec:
    // check that request is still the head before retry (this is sort of the solution to head of line blocking)
    static final class NextopHttpRequestRetryHandler implements HttpRequestRetryHandler {
        private SendStrategy sendStrategy;

        private final MessageControlState.Entry entry;
        private final MessageControlState mcs;


        NextopHttpRequestRetryHandler(SendStrategy sendStrategy,
                                      MessageControlState.Entry entry, MessageControlState mcs) {
            this.sendStrategy = sendStrategy;
            this.entry = entry;
            this.mcs = mcs;
        }


        @Override
        public boolean retryRequest(final IOException exception,
                                 final int executionCount,
                                 final HttpContext context) {
            sendStrategy = sendStrategy.retry();
            if (!sendStrategy.isSend()) {
                return false;
            }

            // check ended - don't retry an ended entry
            if (null != entry.end) {
                return false;
            }

            // retry if not fully sent (server starts processing on fully received message)
            // or if the request is idempotent (either because it is nullipotent or marked as idempotent)
            if (HttpClientContext.adapt(context).isRequestSent() && Message.isIdempotent(entry.message)) {
                return false;
            }

            // fail if there is a higher priority request
            int timeoutMs = (int) sendStrategy.getDelay(TimeUnit.MILLISECONDS);
            try {
                return !mcs.hasFirstAvailable(entry.id, timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return false;
            }
        }

    }



    // implement a subclass of HttpRequestExector that surfaces SendIOException(final chunk), ReceiveIOException
    // implement a custom RetryHandler that always retries if send failed on not final chunk,
    /** based on <code>org.apache.http.impl.execchain.MinimalClientExec</code> */
    static final class NextopClientExec implements ClientExecChain {


//        ProgressCallback progressCallback;

        private final HttpRequestExecutor requestExecutor;
        private final HttpClientConnectionManager connManager;
        private final ConnectionReuseStrategy reuseStrategy;
        private final ConnectionKeepAliveStrategy keepAliveStrategy;
        private final HttpProcessor httpProcessor;

        public NextopClientExec(
                final HttpRequestExecutor requestExecutor,
                final HttpClientConnectionManager connManager,
                final ConnectionReuseStrategy reuseStrategy,
                final ConnectionKeepAliveStrategy keepAliveStrategy) {
            this.httpProcessor = new ImmutableHttpProcessor(
                    new RequestContent(),
                    new RequestTargetHost(),
                    new RequestClientConnControl(),
                    new RequestUserAgent(DEFAULT_USER_AGENT));
            this.requestExecutor    = requestExecutor;
            this.connManager        = connManager;
            this.reuseStrategy      = reuseStrategy;
            this.keepAliveStrategy  = keepAliveStrategy;
//            this.progressCallback = progressCallback;
        }

        static void rewriteRequestURI(
                final HttpRequestWrapper request,
                final HttpRoute route) throws ProtocolException {
            try {
                URI uri = request.getURI();
                if (uri != null) {
                    // Make sure the request URI is relative
                    if (uri.isAbsolute()) {
                        uri = URIUtils.rewriteURI(uri, null, true);
                    } else {
                        uri = URIUtils.rewriteURI(uri);
                    }
                    request.setURI(uri);
                }
            } catch (final URISyntaxException ex) {
                throw new ProtocolException("Invalid URI: " + request.getRequestLine().getUri(), ex);
            }
        }

        @Override
        public CloseableHttpResponse execute(
                final HttpRoute route,
                final HttpRequestWrapper request,
                final HttpClientContext context,
                final HttpExecutionAware execAware) throws IOException, HttpException {
            rewriteRequestURI(request, route);

            final ConnectionRequest connRequest = connManager.requestConnection(route, null);
            if (execAware != null) {
                if (execAware.isAborted()) {
                    connRequest.cancel();
                    throw new RequestAbortedException("Request aborted");
                } else {
                    execAware.setCancellable(connRequest);
                }
            }

            final RequestConfig config = context.getRequestConfig();

            final HttpClientConnection managedConn;
            try {
                final int timeout = config.getConnectionRequestTimeout();
                managedConn = connRequest.get(timeout > 0 ? timeout : 0, TimeUnit.MILLISECONDS);
            } catch(final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new RequestAbortedException("Request aborted", interrupted);
            } catch(final ExecutionException ex) {
                Throwable cause = ex.getCause();
                if (cause == null) {
                    cause = ex;
                }
                throw new RequestAbortedException("Request execution failed", cause);
            }

            final NextopConnectionHolder releaseTrigger = new NextopConnectionHolder(connManager, managedConn);
            try {
                if (execAware != null) {
                    if (execAware.isAborted()) {
                        releaseTrigger.close();
                        throw new RequestAbortedException("Request aborted");
                    } else {
                        execAware.setCancellable(releaseTrigger);
                    }
                }

                if (!managedConn.isOpen()) {
                    final int timeout = config.getConnectTimeout();
                    this.connManager.connect(
                            managedConn,
                            route,
                            timeout > 0 ? timeout : 0,
                            context);
                    this.connManager.routeComplete(managedConn, route, context);
                }
                final int timeout = config.getSocketTimeout();
                if (timeout >= 0) {
                    managedConn.setSocketTimeout(timeout);
                }


                HttpHost target = null;
                final HttpRequest original = request.getOriginal();
                if (original instanceof HttpUriRequest) {
                    final URI uri = ((HttpUriRequest) original).getURI();
                    if (uri.isAbsolute()) {
                        target = new HttpHost(uri.getHost(), uri.getPort(), uri.getScheme());
                    }
                }
                if (target == null) {
                    target = route.getTargetHost();
                }

                context.setAttribute(HttpCoreContext.HTTP_TARGET_HOST, target);
                context.setAttribute(HttpCoreContext.HTTP_REQUEST, request);
                context.setAttribute(HttpCoreContext.HTTP_CONNECTION, managedConn);
                context.setAttribute(HttpClientContext.HTTP_ROUTE, route);


                // TODO managedConn is an intance of CPoolProxy
                // TODO an easy way to call getConnection or get the connection out of it without reflection?

                httpProcessor.process(request, context);
                final HttpResponse response = requestExecutor.execute(request, managedConn, context);
                httpProcessor.process(response, context);

                // The connection is in or can be brought to a re-usable state.
                if (reuseStrategy.keepAlive(response, context)) {
                    // Set the idle duration of this connection
                    final long duration = keepAliveStrategy.getKeepAliveDuration(response, context);
                    releaseTrigger.setValidFor(duration, TimeUnit.MILLISECONDS);
                    releaseTrigger.markReusable();
                } else {
                    releaseTrigger.markNonReusable();
                }

                // check for entity, release connection if possible
                final HttpEntity entity = response.getEntity();
                if (entity == null || !entity.isStreaming()) {
                    // connection not needed and (assumed to be) in re-usable state
                    releaseTrigger.releaseConnection();
                    return new NextopHttpResponseProxy(response, null);
                } else {
                    return new NextopHttpResponseProxy(response, releaseTrigger);
                }
            } catch (final ConnectionShutdownException ex) {
                final InterruptedIOException ioex = new InterruptedIOException(
                        "Connection has been shut down");
                ioex.initCause(ex);
                throw ioex;
            } catch (final HttpException ex) {
                releaseTrigger.abortConnection();
                throw ex;
            } catch (final IOException ex) {
                releaseTrigger.abortConnection();
                throw ex;
            } catch (final RuntimeException ex) {
                releaseTrigger.abortConnection();
                throw ex;
            }
        }

    }


    static final class NextopHttpClientConnectionFactory
            implements HttpConnectionFactory<HttpRoute, ManagedHttpClientConnection> {

        private final HttpMessageWriterFactory<HttpRequest> requestWriterFactory;
        private final HttpMessageParserFactory<HttpResponse> responseParserFactory;
        private final ContentLengthStrategy incomingContentStrategy;
        private final ContentLengthStrategy outgoingContentStrategy;

        private final AtomicInteger connectionCounter = new AtomicInteger(0);

        public NextopHttpClientConnectionFactory(
                @Nullable HttpMessageWriterFactory<HttpRequest> requestWriterFactory,
                @Nullable HttpMessageParserFactory<HttpResponse> responseParserFactory,
                @Nullable ContentLengthStrategy incomingContentStrategy,
                @Nullable ContentLengthStrategy outgoingContentStrategy) {
            super();
            this.requestWriterFactory = requestWriterFactory != null ? requestWriterFactory :
                    DefaultHttpRequestWriterFactory.INSTANCE;
            this.responseParserFactory = responseParserFactory != null ? responseParserFactory :
                    DefaultHttpResponseParserFactory.INSTANCE;
            this.incomingContentStrategy = incomingContentStrategy != null ? incomingContentStrategy :
                    LaxContentLengthStrategy.INSTANCE;
            this.outgoingContentStrategy = outgoingContentStrategy != null ? outgoingContentStrategy :
                    StrictContentLengthStrategy.INSTANCE;
        }

        public NextopHttpClientConnectionFactory(
                @Nullable HttpMessageWriterFactory<HttpRequest> requestWriterFactory,
                @Nullable HttpMessageParserFactory<HttpResponse> responseParserFactory) {
            this(requestWriterFactory, responseParserFactory, null, null);
        }

        public NextopHttpClientConnectionFactory(
                @Nullable HttpMessageParserFactory<HttpResponse> responseParserFactory) {
            this(null, responseParserFactory);
        }

        public NextopHttpClientConnectionFactory() {
            this(null, null);
        }

        @Override
        public NextopHttpClientConnection create(final HttpRoute route, final ConnectionConfig config) {
            final ConnectionConfig cconfig = config != null ? config : ConnectionConfig.DEFAULT;
            CharsetDecoder chardecoder = null;
            CharsetEncoder charencoder = null;
            final Charset charset = cconfig.getCharset();
            final CodingErrorAction malformedInputAction = cconfig.getMalformedInputAction() != null ?
                    cconfig.getMalformedInputAction() : CodingErrorAction.REPORT;
            final CodingErrorAction unmappableInputAction = cconfig.getUnmappableInputAction() != null ?
                    cconfig.getUnmappableInputAction() : CodingErrorAction.REPORT;
            if (charset != null) {
                chardecoder = charset.newDecoder();
                chardecoder.onMalformedInput(malformedInputAction);
                chardecoder.onUnmappableCharacter(unmappableInputAction);
                charencoder = charset.newEncoder();
                charencoder.onMalformedInput(malformedInputAction);
                charencoder.onUnmappableCharacter(unmappableInputAction);
            }
            final String id = String.format("nextop-http-%d", connectionCounter.getAndIncrement());
            return new NextopHttpClientConnection(
                    id,
                    cconfig.getBufferSize(),
                    cconfig.getFragmentSizeHint(),
                    chardecoder,
                    charencoder,
                    cconfig.getMessageConstraints(),
                    incomingContentStrategy,
                    outgoingContentStrategy,
                    requestWriterFactory,
                    responseParserFactory);
        }

    }






    // be able to reset progress
    // be able to attach callback that gets called after A bytes of upload, B bytes of download indiviudally
    static final class NextopHttpClientConnection extends DefaultManagedHttpClientConnection {
        // emit progress every 4KiB
        final int emitQBytes = DEFAULT_EMIT_Q_BYTES;


        private boolean wireSet = false;
        @Nullable
        private Wire wire = null;


        public NextopHttpClientConnection(
                final String id,
                final int buffersize,
                final int fragmentSizeHint,
                final CharsetDecoder chardecoder,
                final CharsetEncoder charencoder,
                final MessageConstraints constraints,
                final ContentLengthStrategy incomingContentStrategy,
                final ContentLengthStrategy outgoingContentStrategy,
                final HttpMessageWriterFactory<HttpRequest> requestWriterFactory,
                final HttpMessageParserFactory<HttpResponse> responseParserFactory) {
            super(id, buffersize, fragmentSizeHint,
                    chardecoder, charencoder,
                    constraints,
                    incomingContentStrategy, outgoingContentStrategy,
                    requestWriterFactory, responseParserFactory);
        }


        @Nullable
        private ProgressCallback getProgressCallback() {
            // TODO passing this via the thread is nasty, but is there a good way for the ExecChain to inject into this
            // TODO (through the pool adapter)
            RequestLooper t = (RequestLooper) Thread.currentThread();
            return t.progressCallback;
        }

        @Nullable
        private Wire.Adapter getAdapter() {
            // TODO (see notes in #getProgressCallback)
            RequestLooper t = (RequestLooper) Thread.currentThread();
            return t.wireAdapter;
        }

        /** sets {@link #wire} from the socket input/output,
         * if there is an adapter (which is most commonly used to condition the wire).
         * After this call, {@link #wire} may be null. */
        private void setWire(Socket socket) throws IOException {
            if (!wireSet) {
                wireSet = true;

                @Nullable Wire.Adapter adapter = getAdapter();
                if (null != adapter) {
                    InputStream is = super.getSocketInputStream(socket);
                    OutputStream os = super.getSocketOutputStream(socket);
                    wire = adapter.adapt(Wires.io(is, os));
                }
            }
        }

        @Override
        protected InputStream getSocketInputStream(Socket socket) throws IOException {
            setWire(socket);
            if (null != wire) {
                return Wires.inputStream(wire);
            } else {
                return super.getSocketInputStream(socket);
            }
        }

        @Override
        protected OutputStream getSocketOutputStream(Socket socket) throws IOException {
            setWire(socket);
            if (null != wire) {
                return Wires.outputStream(wire);
            } else {
                return super.getSocketOutputStream(socket);
            }
        }


        // FIXME if TCP error on close, throw SendIOException
        // FIXME this means all packets sent up to the tcp window size,
        // FIXME but failed to ack the end
        // FIXME otherwise, up to the end of the entity was not sent, so the server knows it has a hanging request
        @Override
        protected OutputStream createOutputStream(final long len, SessionOutputBuffer outbuffer) {
            @Nullable final ProgressCallback progressCallback = getProgressCallback();

            final long sendTotalBytes = 0 < len ? len : 0;

            final OutputStream os = super.createOutputStream(len, outbuffer);
            return new OutputStream() {
                long sentBytes = 0L;
                long lastNotificationIndex = -1L;


                /** scales the total in the case the actual transfer is exceeding the total (bug in the size calc) */
                private long scaledSendTotalBytes(long b) {
                    long t = sendTotalBytes;
                    while (0 < t && t <= b) {
                        long u = 161 * t / 100;
                        if (t < u) {
                            t = u;
                        } else {
                            t *= 2;
                        }
                    }
                    return t;
                }


                private void onSendProgress(long bytes) {
                    sentBytes += bytes;

                    if (null != progressCallback) {
                        long notificationIndex = sentBytes / emitQBytes;
                        if (lastNotificationIndex != notificationIndex) {
                            lastNotificationIndex = notificationIndex;
                            progressCallback.onSendProgress(sentBytes, scaledSendTotalBytes(sentBytes));
                        }
                    }
                }
                private void onSendCompleted() {
                    if (null != progressCallback) {
                        progressCallback.onSendCompleted(sentBytes, sentBytes);
                    }
                }


                @Override
                public void write(int b) throws IOException {
                    os.write(b);
                    onSendProgress(1);
                }

                @Override
                public void write(byte[] b) throws IOException {
                    write(b, 0, b.length);
                }

                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    for (int i = 0; i < len; i += emitQBytes) {
                        int c = Math.min(emitQBytes, len - i);
                        os.write(b, off + i, c);
                        onSendProgress(c);

                        // FIXME
//                        try {
//                            Thread.sleep(200);
//                        } catch (InterruptedException e) {
//                            // ignore
//                        }
                    }
                }

                @Override
                public void flush() throws IOException {
                    os.flush();
                }

                @Override
                public void close() throws IOException {
                    os.close();
                    onSendCompleted();
                }
            };
        }


        @Override
        public void sendRequestEntity(HttpEntityEnclosingRequest request) throws HttpException, IOException {
            super.sendRequestEntity(request);
        }

        @Override
        protected InputStream createInputStream(final long len, SessionInputBuffer inbuffer) {
            @Nullable final ProgressCallback progressCallback = getProgressCallback();

            final long receiveTotalBytes = 0 < len ? len : 0;

            final InputStream is = super.createInputStream(len, inbuffer);
            return new InputStream() {
                long receivedBytes = 0L;
                long lastNotificationIndex = -1L;


                /** scales the total in the case the actual transfer is exceeding the total (bug in the size calc) */
                private long scaledReceiveTotalBytes(long b) {
                    long t = receiveTotalBytes;
                    while (0 < t && t <= b) {
                        long u = 161 * t / 100;
                        if (t < u) {
                            t = u;
                        } else {
                            t *= 2;
                        }
                    }
                    return t;
                }


                private void onReceiveProgress(long bytes) {
                    receivedBytes += bytes;

                    if (null != progressCallback) {
                        long notificationIndex = receivedBytes / emitQBytes;
                        if (lastNotificationIndex != notificationIndex) {
                            lastNotificationIndex = notificationIndex;
                            progressCallback.onReceiveProgress(receivedBytes, scaledReceiveTotalBytes(receivedBytes));
                        }
                    }
                }
                private void onReceiveCompleted() {
                    if (null != progressCallback) {
                        progressCallback.onReceiveCompleted(receivedBytes, receivedBytes);
                    }
                }




                @Override
                public int read() throws IOException {
                    int b = is.read();
                    onReceiveProgress(1);
                    return b;
                }

                @Override
                public int read(byte[] b) throws IOException {
                    return read(b, 0, b.length);
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    for (int i = 0; i < len; i += emitQBytes) {
                        int c = Math.min(emitQBytes, len - i);
                        int r = is.read(b, off + i, c);
                        if (0 < r) {
                            onReceiveProgress(r);
                        }
                        if (r < c) {
                            return i + r;
                        }
                    }
                    return len;
                }

                @Override
                public void close() throws IOException {
                    super.close();
                    onReceiveCompleted();
                }



                @Override
                public long skip(long n) throws IOException {
                    return is.skip(n);
                }

                @Override
                public int available() throws IOException {
                    return is.available();
                }

                @Override
                public boolean markSupported() {
                    return is.markSupported();
                }

                @Override
                public void mark(int readlimit) {
                    is.mark(readlimit);
                }

                @Override
                public void reset() throws IOException {
                    is.reset();
                }
            };
        }


        // OVERRIDE sendRequestEntity
        // throw a SendIO
    }


    // not to be shared. one per exec chain/request
    static class NextopHttpRequestExecutor extends HttpRequestExecutor {


        ProgressCallback progressCallback;
        int sendTryCount = 0;
        int receiveTryCount = 0;


        NextopHttpRequestExecutor(ProgressCallback progressCallback) {
            this.progressCallback = progressCallback;
        }


        @Override
        protected HttpResponse doSendRequest(
                final HttpRequest request,
                final HttpClientConnection conn,
                final HttpContext context) throws IOException, HttpException {
            ++sendTryCount;
            if (null != progressCallback) {
                progressCallback.onSendStarted(sendTryCount);
            }
            return super.doSendRequest(request, conn, context);
        }

        @Override
        protected HttpResponse doReceiveResponse(
                final HttpRequest request,
                final HttpClientConnection conn,
                final HttpContext context) throws HttpException, IOException {
            ++receiveTryCount;
            if (null != progressCallback) {
                progressCallback.onReceiveStarted(receiveTryCount);
            }
            return super.doReceiveResponse(request, conn, context);
        }
    }



}
