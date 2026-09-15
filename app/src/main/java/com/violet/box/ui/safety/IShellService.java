package com.violet.box.ui.safety;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

/**
 * Hand-written equivalent of the former IShellService.aidl.
 *
 * Why this exists: the sandbox used to build this app can reach Maven mirrors but not
 * Google's SDK repository, so there is no `aidl` binary available. The interface is small
 * enough to write out by hand, which lets us drop `buildFeatures { aidl = true }` and build
 * without the tool.
 *
 * Transaction ids are copied verbatim from the .aidl (`= 0` and `= 16777114`) so the wire
 * protocol is byte-identical - do NOT renumber these.
 */
public interface IShellService extends IInterface {

    /** Local side default impl. */
    class Default implements IShellService {
        @Override
        public String[] runCommand(String[] cmd, long deadlineElapsedMs) throws RemoteException {
            return null;
        }

        @Override
        public void destroy() throws RemoteException {
        }

        @Override
        public IBinder asBinder() {
            return null;
        }
    }

    abstract class Stub extends Binder implements IShellService {
        private static final String DESCRIPTOR = "com.violet.box.ui.safety.IShellService";

        static final int TRANSACTION_runCommand = 0;
        static final int TRANSACTION_destroy = 16777114;

        public Stub() {
            attachInterface(this, DESCRIPTOR);
        }

        public static IShellService asInterface(IBinder obj) {
            if (obj == null) {
                return null;
            }
            IInterface local = obj.queryLocalInterface(DESCRIPTOR);
            if (local != null && (local instanceof IShellService)) {
                return (IShellService) local;
            }
            return new Proxy(obj);
        }

        @Override
        public IBinder asBinder() {
            return this;
        }

        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            switch (code) {
                case INTERFACE_TRANSACTION:
                    reply.writeString(DESCRIPTOR);
                    return true;
                case TRANSACTION_runCommand: {
                    data.enforceInterface(DESCRIPTOR);
                    String[] arg0 = data.createStringArray();
                    long arg1 = data.readLong();
                    String[] result = this.runCommand(arg0, arg1);
                    reply.writeNoException();
                    reply.writeStringArray(result);
                    return true;
                }
                case TRANSACTION_destroy: {
                    data.enforceInterface(DESCRIPTOR);
                    this.destroy();
                    reply.writeNoException();
                    return true;
                }
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }

        private static class Proxy implements IShellService {
            private final IBinder mRemote;

            Proxy(IBinder remote) {
                mRemote = remote;
            }

            @Override
            public IBinder asBinder() {
                return mRemote;
            }

            public String getInterfaceDescriptor() {
                return DESCRIPTOR;
            }

            @Override
            public String[] runCommand(String[] cmd, long deadlineElapsedMs)
                    throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                String[] result;
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeStringArray(cmd);
                    data.writeLong(deadlineElapsedMs);
                    mRemote.transact(TRANSACTION_runCommand, data, reply, 0);
                    reply.readException();
                    result = reply.createStringArray();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
                return result;
            }

            @Override
            public void destroy() throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_destroy, data, reply, 0);
                    reply.readException();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }
        }
    }

    String[] runCommand(String[] cmd, long deadlineElapsedMs) throws RemoteException;

    void destroy() throws RemoteException;
}
