package org.kontalk.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Queue;

import org.kontalk.authenticator.Authenticator;
import org.kontalk.client.*;

import com.google.protobuf.MessageLite;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;


/**
 * Manages a queue of outgoing requests, including messages to be sent.
 * @author Daniele Ricci
 * @version 1.0
 */
public class RequestWorker extends Thread {
    private static final String TAG = RequestWorker.class.getSimpleName();
    private static final int MSG_REQUEST_JOB = 1;

    private static final long DEFAULT_RETRY_DELAY = 10000;

    private PauseHandler mHandler;

    // content observers for canceling message sendings
    private Handler mObserverHandler;
    private ObserverHandlerThread mObserverThread;

    private final Context mContext;
    private final EndpointServer mServer;
    private String mAuthToken;

    private RequestClient mClient;
    private RequestListenerList mListeners = new RequestListenerList();

    /** Pending jobs queue - will be used on thread start to initialize the messages. */
    static public LinkedList<RequestJob> pendingJobs = new LinkedList<RequestJob>();

    public RequestWorker(Context context, EndpointServer server) {
        mContext = context;
        mServer = server;
    }

    public void addListener(RequestListener listener) {
        if (!this.mListeners.contains(listener))
            this.mListeners.add(listener);
    }

    public void removeListener(RequestListener listener) {
        this.mListeners.remove(listener);
    }

    private final class ObserverHandlerThread extends Thread {
        @Override
        public void run() {
            Log.w(TAG, "observer handler thread started.");
            Looper.prepare();
            mObserverHandler = new Handler();
            Looper.loop();
        }

        public void quit() {
            Log.w(TAG, "observer handler thread quitting");
            mObserverHandler.getLooper().quit();
        }
    }

    public void run() {
        Looper.prepare();
        mAuthToken = Authenticator.getDefaultAccountToken(mContext);
        if (mAuthToken == null) {
            Log.w(TAG, "invalid token - exiting");
            return;
        }

        Log.i(TAG, "using token: " + mAuthToken);

        // start the content observer thread
        Log.w(TAG, "starting observer handler thread");
        mObserverThread = new ObserverHandlerThread();
        mObserverThread.start();

        Log.d(TAG, "using server " + mServer.toString());
        mClient = new RequestClient(mContext, mServer, mAuthToken);

        // create handler and empty pending jobs queue
        // this must be done synchronized on the queue
        synchronized (pendingJobs) {
            mHandler = new PauseHandler(new LinkedList<RequestJob>(pendingJobs));
            pendingJobs = new LinkedList<RequestJob>();
        }

        Looper.loop();
    }

    /** A fake listener to call all the listeners inside the collection. */
    private final class RequestListenerList extends ArrayList<RequestListener>
            implements RequestListener {
        private static final long serialVersionUID = 1L;

        @Override
        public void downloadProgress(RequestJob job, long bytes) {
            for (RequestListener l : this)
                l.downloadProgress(job, bytes);
        }

        @Override
        public boolean error(RequestJob job, Throwable exc) {
            boolean requeue = false;
            for (RequestListener l : this)
                if (l.error(job, exc))
                    requeue = true;

            return requeue;
        }

        @Override
        public void response(RequestJob job, MessageLite response) {
            for (RequestListener l : this) {
                l.response(job, response);
            }
        }

        @Override
        public void uploadProgress(RequestJob job, long bytes) {
            for (RequestListener l : this)
                l.uploadProgress(job, bytes);
        }
    }

    private final class PauseHandler extends Handler {
        private boolean mRunning;

        public PauseHandler(Queue<RequestJob> pending) {
            mRunning = true;

            // requeue the old messages
            Log.i(TAG, "processing pending jobs queue (" + pending.size() + " jobs)");
            for (RequestJob job = pending.poll(); job != null; job = pending.poll()) {
                Log.i(TAG, "requeueing pending job " + job);
                sendMessage(obtainMessage(MSG_REQUEST_JOB, job));
            }
        }

        public synchronized void stop() {
            mRunning = false;
            mClient.abort();
            getLooper().quit();
        }

        @Override
        public synchronized void handleMessage(Message msg) {
            if (msg.what == MSG_REQUEST_JOB) {
                // not running - queue message
                if (!mRunning) {
                    Log.w(TAG, "request worker is not running - dropping message");
                    return;
                }

                RequestJob job = (RequestJob) msg.obj;
                Log.d(TAG, "JOB: " + job.toString());

                // check now if job has been canceled
                if (job.isCanceled()) {
                    Log.w(TAG, "request has been canceled - dropping");
                    return;
                }

                // try to use the custom listener
                RequestListener listener = job.getListener();
                if (listener != null)
                    addListener(listener);

                MessageLite response;
                try {
                    // FIXME this should be abstracted/delegated some way
                    if (job instanceof MessageSender) {
                        MessageSender mess = (MessageSender) job;
                        // observe the content for cancel requests
                        mess.observe(mContext, mObserverHandler);
                    }

                    response = job.call(mClient, mListeners, mContext);

                    mListeners.response(job, response);
                }
                catch (IOException e) {
                    boolean requeue = true;
                    Log.e(TAG, "request error", e);
                    requeue = mListeners.error(job, e);

                    if (requeue) {
                        Log.d(TAG, "requeuing job " + job);
                        push(job, DEFAULT_RETRY_DELAY);
                    }
                }
                finally {
                    // unobserve if necessary
                    if (job != null && job instanceof MessageSender) {
                        MessageSender mess = (MessageSender) job;
                        mess.unobserve(mContext);
                    }

                    // remove our old custom listener
                    if (listener != null)
                        removeListener(listener);
                }
            }

            else
                super.handleMessage(msg);
        };
    }

    public void push(RequestJob job) {
        push(job, 0);
    }

    public void push(RequestJob job, long delay) {
        // max wait time 10 seconds
        int retries = 20;

        while(!isAlive() || mHandler == null || mObserverHandler == null || retries <= 0) {
            try {
                // 500ms should do the job...
                Thread.sleep(500);
                Thread.yield();
                retries--;
            } catch (InterruptedException e) {
                // interrupted - do not send message
                return;
            }
        }

        mHandler.sendMessageDelayed(
                mHandler.obtainMessage(MSG_REQUEST_JOB, job),
                delay);
    }

    /** Returns true if the worker is running. */
    public boolean isRunning() {
        return (mHandler != null && mHandler.mRunning);
    }

    /** Shuts down this request worker gracefully. */
    public void shutdown() {
        Log.w(TAG, "shutting down");
        if (mHandler != null) {
            mHandler.stop();
            mHandler = null;
        }

        if (mObserverThread != null) {
            mObserverThread.quit();
            mObserverThread = null;
        }

        Log.w(TAG, "exiting");
    }
}
