/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.support.v4.media.session;

import android.os.*;
import android.support.v4.media.session.MediaSessionCompat.QueueItem;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Transfer a large list of QueueItem objects across an IPC.  Splits into
 * multiple transactions if needed.
 *
 * @hide
 */
public class QueueItemListSlice implements Parcelable {
    private static String TAG = "ParceledListSlice";
    private static boolean DEBUG = false;

    /*
     * TODO get this number from somewhere else. For now set it to a quarter of
     * the 1MB limit.
     */
    private static final int MAX_IPC_SIZE = 64 * 1024;// IBinder.MAX_IPC_SIZE;

    private final List<QueueItem> mList;

    public QueueItemListSlice(List<QueueItem> list) {
        mList = list;
    }

    private QueueItemListSlice(Parcel p) {
        final int N = p.readInt();
        mList = new ArrayList<QueueItem>(N);
        if (DEBUG) Log.d(TAG, "Retrieving " + N + " items");
        if (N <= 0) {
            return;
        }

        Parcelable.Creator<QueueItem> creator = QueueItem.CREATOR;

        int i = 0;
        while (i < N) {
            if (p.readInt() == 0) {
                break;
            }

            final QueueItem parcelable = creator.createFromParcel(p);

            mList.add(parcelable);

            if (DEBUG) Log.d(TAG, "Read inline #" + i + ": " + mList.get(mList.size()-1));
            i++;
        }
        if (i >= N) {
            return;
        }
        final IBinder retriever = p.readStrongBinder();
        while (i < N) {
            if (DEBUG) Log.d(TAG, "Reading more @" + i + " of " + N + ": retriever=" + retriever);
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            data.writeInt(i);
            try {
                retriever.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0);
            } catch (RemoteException e) {
                Log.w(TAG, "Failure retrieving array; only received " + i + " of " + N, e);
                return;
            }
            while (i < N && reply.readInt() != 0) {
                final QueueItem parcelable = creator.createFromParcel(reply);

                mList.add(parcelable);

                if (DEBUG) Log.d(TAG, "Read extra #" + i + ": " + mList.get(mList.size()-1));
                i++;
            }
            reply.recycle();
            data.recycle();
        }
    }

    public List<QueueItem> getList() {
        return mList;
    }

    @Override
    public int describeContents() {
        int contents = 0;
        for (int i=0; i<mList.size(); i++) {
            contents |= mList.get(i).describeContents();
        }
        return contents;
    }

    /**
     * Write this to another Parcel. Note that this discards the internal Parcel
     * and should not be used anymore. This is so we can pass this to a Binder
     * where we won't have a chance to call recycle on this.
     */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        final int N = mList.size();
        final int callFlags = flags;
        dest.writeInt(N);
        if (DEBUG) Log.d(TAG, "Writing " + N + " items");
        if (N > 0) {
            int i = 0;
            while (i < N && dest.dataSize() < MAX_IPC_SIZE) {
                dest.writeInt(1);

                final QueueItem parcelable = mList.get(i);
                parcelable.writeToParcel(dest, callFlags);

                if (DEBUG) Log.d(TAG, "Wrote inline #" + i + ": " + mList.get(i));
                i++;
            }
            if (i < N) {
                dest.writeInt(0);
                Binder retriever = new Binder() {
                    @Override
                    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                            throws RemoteException {
                        if (code != FIRST_CALL_TRANSACTION) {
                            return super.onTransact(code, data, reply, flags);
                        }
                        int i = data.readInt();
                        if (DEBUG) Log.d(TAG, "Writing more @" + i + " of " + N);
                        while (i < N && reply.dataSize() < MAX_IPC_SIZE) {
                            reply.writeInt(1);

                            final QueueItem parcelable = mList.get(i);
                            parcelable.writeToParcel(reply, callFlags);

                            if (DEBUG) Log.d(TAG, "Wrote extra #" + i + ": " + mList.get(i));
                            i++;
                        }
                        if (i < N) {
                            if (DEBUG) Log.d(TAG, "Breaking @" + i + " of " + N);
                            reply.writeInt(0);
                        }
                        return true;
                    }
                };
                if (DEBUG) Log.d(TAG, "Breaking @" + i + " of " + N + ": retriever=" + retriever);
                dest.writeStrongBinder(retriever);
            }
        }
    }

    public static final Parcelable.Creator<QueueItemListSlice> CREATOR =
            new Parcelable.Creator<QueueItemListSlice>() {
        public QueueItemListSlice createFromParcel(Parcel in) {
            return new QueueItemListSlice(in);
        }

        public QueueItemListSlice[] newArray(int size) {
            return new QueueItemListSlice[size];
        }
    };
}
