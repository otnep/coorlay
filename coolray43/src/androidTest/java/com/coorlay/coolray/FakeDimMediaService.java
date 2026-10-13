package com.coorlay.coolray;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import java.util.ArrayList;

/**
 * Stands in for extraservice's cluster media relay in ClusterMediaTest: decodes IDimTransListener exactly as
 * its MediaService stub does (firmware 10500) and records each call. Java, because it runs in the test
 * package's own process, which has neither Kotlin nor the second DEX file.
 */
public class FakeDimMediaService extends Service {
    static final String DESCRIPTOR = "com.ecarx.xui.adaptapi.diminteraction.IDimTransListener";
    static final int DUMP = 999;
    private static final ArrayList<String> CALLS = new ArrayList<String>();

    private final Binder binder = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws android.os.RemoteException {
            if (code == DUMP) {
                synchronized (CALLS) {
                    reply.writeStringList(new ArrayList<String>(CALLS));
                    CALLS.clear();
                }
                return true;
            }
            data.enforceInterface(DESCRIPTOR);
            String call;
            switch (code) {
                case 1: call = "progress " + data.readInt() + " " + data.readInt() + " " + data.readString(); break;
                case 2: call = "info " + data.readInt() + " " + data.readString() + "|" + data.readString() + "|"
                        + data.readString() + "|" + data.readString() + " " + data.readInt(); break;
                case 3: call = "status " + data.readInt() + " " + data.readInt(); break;
                case 5: call = "source " + data.readInt(); break;
                default: return super.onTransact(code, data, reply, flags);
            }
            synchronized (CALLS) { CALLS.add(call); }
            reply.writeNoException();
            return true;
        }
    };

    @Override
    public IBinder onBind(Intent intent) { return binder; }
}
