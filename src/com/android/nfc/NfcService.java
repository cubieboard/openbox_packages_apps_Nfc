/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.nfc;

import com.android.internal.nfc.LlcpServiceSocket;
import com.android.internal.nfc.LlcpSocket;
import com.android.nfc.mytag.MyTagClient;
import com.android.nfc.mytag.MyTagServer;

import android.app.Activity;
import android.app.Application;
import android.app.PendingIntent;
import android.app.PendingIntent.CanceledException;
import android.app.StatusBarManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.nfc.ErrorCodes;
import android.nfc.FormatException;
import android.nfc.ILlcpConnectionlessSocket;
import android.nfc.ILlcpServiceSocket;
import android.nfc.ILlcpSocket;
import android.nfc.INfcAdapter;
import android.nfc.INfcSecureElement;
import android.nfc.INfcTag;
import android.nfc.IP2pInitiator;
import android.nfc.IP2pTarget;
import android.nfc.LlcpPacket;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.ListIterator;
import java.util.Timer;
import java.util.TimerTask;

public class NfcService extends Application {
    static final boolean DBG = false;

    private static final String MY_TAG_FILE_NAME = "mytag";

    static {
        System.loadLibrary("nfc_jni");
    }

    /**
     * NFC Forum "URI Record Type Definition"
     *
     * This is a mapping of "URI Identifier Codes" to URI string prefixes,
     * per section 3.2.2 of the NFC Forum URI Record Type Definition document.
     */
    private static final String[] URI_PREFIX_MAP = new String[] {
            "", // 0x00
            "http://www.", // 0x01
            "https://www.", // 0x02
            "http://", // 0x03
            "https://", // 0x04
            "tel:", // 0x05
            "mailto:", // 0x06
            "ftp://anonymous:anonymous@", // 0x07
            "ftp://ftp.", // 0x08
            "ftps://", // 0x09
            "sftp://", // 0x0A
            "smb://", // 0x0B
            "nfs://", // 0x0C
            "ftp://", // 0x0D
            "dav://", // 0x0E
            "news:", // 0x0F
            "telnet://", // 0x10
            "imap:", // 0x11
            "rtsp://", // 0x12
            "urn:", // 0x13
            "pop:", // 0x14
            "sip:", // 0x15
            "sips:", // 0x16
            "tftp:", // 0x17
            "btspp://", // 0x18
            "btl2cap://", // 0x19
            "btgoep://", // 0x1A
            "tcpobex://", // 0x1B
            "irdaobex://", // 0x1C
            "file://", // 0x1D
            "urn:epc:id:", // 0x1E
            "urn:epc:tag:", // 0x1F
            "urn:epc:pat:", // 0x20
            "urn:epc:raw:", // 0x21
            "urn:epc:", // 0x22
    };

    public static final String SERVICE_NAME = "nfc";

    private static final String TAG = "NfcService";

    private static final String NFC_PERM = android.Manifest.permission.NFC;
    private static final String NFC_PERM_ERROR = "NFC permission required";
    private static final String ADMIN_PERM = android.Manifest.permission.WRITE_SECURE_SETTINGS;
    private static final String ADMIN_PERM_ERROR = "WRITE_SECURE_SETTINGS permission required";

    private static final String PREF = "NfcServicePrefs";

    private static final String PREF_NFC_ON = "nfc_on";
    private static final boolean NFC_ON_DEFAULT = true;

    private static final String PREF_SECURE_ELEMENT_ON = "secure_element_on";
    private static final boolean SECURE_ELEMENT_ON_DEFAULT = false;

    private static final String PREF_SECURE_ELEMENT_ID = "secure_element_id";
    private static final int SECURE_ELEMENT_ID_DEFAULT = 0;

    private static final String PREF_LLCP_LTO = "llcp_lto";
    private static final int LLCP_LTO_DEFAULT = 150;
    private static final int LLCP_LTO_MAX = 255;

    /** Maximum Information Unit */
    private static final String PREF_LLCP_MIU = "llcp_miu";
    private static final int LLCP_MIU_DEFAULT = 128;
    private static final int LLCP_MIU_MAX = 2176;

    /** Well Known Service List */
    private static final String PREF_LLCP_WKS = "llcp_wks";
    private static final int LLCP_WKS_DEFAULT = 1;
    private static final int LLCP_WKS_MAX = 15;

    private static final String PREF_LLCP_OPT = "llcp_opt";
    private static final int LLCP_OPT_DEFAULT = 0;
    private static final int LLCP_OPT_MAX = 3;

    private static final String PREF_DISCOVERY_A = "discovery_a";
    private static final boolean DISCOVERY_A_DEFAULT = true;

    private static final String PREF_DISCOVERY_B = "discovery_b";
    private static final boolean DISCOVERY_B_DEFAULT = true;

    private static final String PREF_DISCOVERY_F = "discovery_f";
    private static final boolean DISCOVERY_F_DEFAULT = true;

    private static final String PREF_DISCOVERY_15693 = "discovery_15693";
    private static final boolean DISCOVERY_15693_DEFAULT = true;

    private static final String PREF_DISCOVERY_NFCIP = "discovery_nfcip";
    private static final boolean DISCOVERY_NFCIP_DEFAULT = true;

    /** NFC Reader Discovery mode for enableDiscovery() */
    private static final int DISCOVERY_MODE_READER = 0;

    /** Card Emulation Discovery mode for enableDiscovery() */
    private static final int DISCOVERY_MODE_CARD_EMULATION = 2;

    private static final int LLCP_SERVICE_SOCKET_TYPE = 0;
    private static final int LLCP_SOCKET_TYPE = 1;
    private static final int LLCP_CONNECTIONLESS_SOCKET_TYPE = 2;
    private static final int LLCP_SOCKET_NB_MAX = 5;  // Maximum number of socket managed
    private static final int LLCP_RW_MAX_VALUE = 15;  // Receive Window

    private static final int PROPERTY_LLCP_LTO = 0;
    private static final String PROPERTY_LLCP_LTO_VALUE = "llcp.lto";
    private static final int PROPERTY_LLCP_MIU = 1;
    private static final String PROPERTY_LLCP_MIU_VALUE = "llcp.miu";
    private static final int PROPERTY_LLCP_WKS = 2;
    private static final String PROPERTY_LLCP_WKS_VALUE = "llcp.wks";
    private static final int PROPERTY_LLCP_OPT = 3;
    private static final String PROPERTY_LLCP_OPT_VALUE = "llcp.opt";
    private static final int PROPERTY_NFC_DISCOVERY_A = 4;
    private static final String PROPERTY_NFC_DISCOVERY_A_VALUE = "discovery.iso14443A";
    private static final int PROPERTY_NFC_DISCOVERY_B = 5;
    private static final String PROPERTY_NFC_DISCOVERY_B_VALUE = "discovery.iso14443B";
    private static final int PROPERTY_NFC_DISCOVERY_F = 6;
    private static final String PROPERTY_NFC_DISCOVERY_F_VALUE = "discovery.felica";
    private static final int PROPERTY_NFC_DISCOVERY_15693 = 7;
    private static final String PROPERTY_NFC_DISCOVERY_15693_VALUE = "discovery.iso15693";
    private static final int PROPERTY_NFC_DISCOVERY_NFCIP = 8;
    private static final String PROPERTY_NFC_DISCOVERY_NFCIP_VALUE = "discovery.nfcip";

    static final int MSG_NDEF_TAG = 0;
    static final int MSG_CARD_EMULATION = 1;
    static final int MSG_LLCP_LINK_ACTIVATION = 2;
    static final int MSG_LLCP_LINK_DEACTIVATED = 3;
    static final int MSG_TARGET_DESELECTED = 4;
    static final int MSG_SHOW_MY_TAG_ICON = 5;
    static final int MSG_HIDE_MY_TAG_ICON = 6;
    static final int MSG_MOCK_NDEF = 7;
    static final int MSG_SE_FIELD_ACTIVATED = 8;
    static final int MSG_SE_FIELD_DEACTIVATED = 9;

    // Locked on mNfcAdapter
    IntentFilter[] mDispatchOverrideFilters;
    PendingIntent mDispatchOverrideIntent;

    // TODO: none of these appear to be synchronized but are
    // read/written from different threads (notably Binder threads)...
    private final LinkedList<RegisteredSocket> mRegisteredSocketList = new LinkedList<RegisteredSocket>();
    private int mLlcpLinkState = NfcAdapter.LLCP_LINK_STATE_DEACTIVATED;
    private int mGeneratedSocketHandle = 0;
    private int mNbSocketCreated = 0;
    private volatile boolean mIsNfcEnabled = false;
    private int mSelectedSeId = 0;
    private boolean mNfcSecureElementState;

    // Secure element
    private Timer mTimerOpenSmx;
    private boolean isClosed = false;
    private boolean isOpened = false;
    private boolean mOpenSmxPending = false;
    private NativeNfcSecureElement mSecureElement;
    private int mSecureElementHandle;

    // fields below are used in multiple threads and protected by synchronized(this)
    private final HashMap<Integer, Object> mObjectMap = new HashMap<Integer, Object>();
    private final HashMap<Integer, Object> mSocketMap = new HashMap<Integer, Object>();
    private boolean mScreenOn;

    // fields below are final after onCreate()
    Context mContext;
    private NativeNfcManager mManager;
    private SharedPreferences mPrefs;
    private SharedPreferences.Editor mPrefsEditor;
    private PowerManager.WakeLock mWakeLock;
    private MyTagServer mMyTagServer;
    private MyTagClient mMyTagClient;

    private static NfcService sService;

    public static NfcService getInstance() {
        return sService;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        Log.i(TAG, "Starting NFC service");

        sService = this;

        mContext = this;
        mManager = new NativeNfcManager(mContext, this);
        mManager.initializeNativeStructure();

        mMyTagServer = new MyTagServer();
        mMyTagClient = new MyTagClient(this);

        mSecureElement = new NativeNfcSecureElement();

        mPrefs = mContext.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        mPrefsEditor = mPrefs.edit();

        mIsNfcEnabled = false;  // real preference read later

        PowerManager pm = (PowerManager)getSystemService(Context.POWER_SERVICE);
        mScreenOn = pm.isScreenOn();
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NfcService");

        ServiceManager.addService(SERVICE_NAME, mNfcAdapter);

        IntentFilter filter = new IntentFilter(NativeNfcManager.INTERNAL_TARGET_DESELECTED_ACTION);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        mContext.registerReceiver(mReceiver, filter);

        Thread t = new Thread() {
            @Override
            public void run() {
                boolean nfc_on = mPrefs.getBoolean(PREF_NFC_ON, NFC_ON_DEFAULT);
                if (nfc_on) {
                    _enable(false);
                }
            }
        };
        t.start();
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
        // NFC application is persistent, it should not be destroyed by framework
        Log.wtf(TAG, "NFC service is under attack!");
    }

    private final INfcAdapter.Stub mNfcAdapter = new INfcAdapter.Stub() {
        /** Protected by "this" */
        NdefMessage mLocalMessage = null;

        @Override
        public boolean enable() throws RemoteException {
            mContext.enforceCallingOrSelfPermission(ADMIN_PERM, ADMIN_PERM_ERROR);

            boolean isSuccess = false;
            boolean previouslyEnabled = isEnabled();
            if (!previouslyEnabled) {
                reset();
                isSuccess = _enable(previouslyEnabled);
            }
            return isSuccess;
        }

        @Override
        public boolean disable() throws RemoteException {
            boolean isSuccess = false;
            mContext.enforceCallingOrSelfPermission(ADMIN_PERM, ADMIN_PERM_ERROR);
            boolean previouslyEnabled = isEnabled();
            if (DBG) Log.d(TAG, "Disabling NFC.  previous=" + previouslyEnabled);

            if (previouslyEnabled) {
                /* tear down the my tag server */
                mMyTagServer.stop();
                isSuccess = mManager.deinitialize();
                if (DBG) Log.d(TAG, "NFC success of deinitialize = " + isSuccess);
                if (isSuccess) {
                    mIsNfcEnabled = false;
                    synchronized (this) {
                        // Clear out any old dispatch overrides
                        mDispatchOverrideFilters = null;
                        mDispatchOverrideIntent = null;
                    }
                }
            }

            updateNfcOnSetting(previouslyEnabled);

            return isSuccess;
        }

        @Override
        public void enableForegroundDispatch(ComponentName activity, PendingIntent intent,
                IntentFilter[] filters) {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);
            synchronized (this) {
                if (activity == null || filters == null || filters.length == 0 || intent == null) {
                    throw new IllegalArgumentException();
                }
                if (mDispatchOverrideFilters != null) {
                    Log.e(TAG, "Replacing active dispatch overrides");
                }
                mDispatchOverrideFilters = filters;
                mDispatchOverrideIntent = intent;
            }
        }

        @Override
        public void disableForegroundDispatch(ComponentName activity) {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);
            synchronized (this) {
                if (mDispatchOverrideFilters == null && mDispatchOverrideIntent == null) {
                    Log.e(TAG, "No active foreground dispatching");
                }
                mDispatchOverrideFilters = null;
                mDispatchOverrideIntent = null;
            }
        }

        @Override
        public int createLlcpConnectionlessSocket(int sap) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* Check SAP is not already used */

            /* Check nb socket created */
            if (mNbSocketCreated < LLCP_SOCKET_NB_MAX) {
                /* Store the socket handle */
                int sockeHandle = mGeneratedSocketHandle;

                if (mLlcpLinkState == NfcAdapter.LLCP_LINK_STATE_ACTIVATED) {
                    NativeLlcpConnectionlessSocket socket;

                    socket = mManager.doCreateLlcpConnectionlessSocket(sap);
                    if (socket != null) {
                        synchronized(NfcService.this) {
                            /* Update the number of socket created */
                            mNbSocketCreated++;

                            /* Add the socket into the socket map */
                            mSocketMap.put(sockeHandle, socket);
                        }
                        return sockeHandle;
                    } else {
                        /*
                         * socket creation error - update the socket handle
                         * generation
                         */
                        mGeneratedSocketHandle -= 1;

                        /* Get Error Status */
                        int errorStatus = mManager.doGetLastError();

                        switch (errorStatus) {
                            case ErrorCodes.ERROR_BUFFER_TO_SMALL:
                                return ErrorCodes.ERROR_BUFFER_TO_SMALL;
                            case ErrorCodes.ERROR_INSUFFICIENT_RESOURCES:
                                return ErrorCodes.ERROR_INSUFFICIENT_RESOURCES;
                            default:
                                return ErrorCodes.ERROR_SOCKET_CREATION;
                        }
                    }
                } else {
                    /* Check SAP is not already used */
                    if (!CheckSocketSap(sap)) {
                        return ErrorCodes.ERROR_SAP_USED;
                    }

                    NativeLlcpConnectionlessSocket socket = new NativeLlcpConnectionlessSocket(sap);

                    synchronized(NfcService.this) {
                        /* Add the socket into the socket map */
                        mSocketMap.put(sockeHandle, socket);

                        /* Update the number of socket created */
                        mNbSocketCreated++;
                    }
                    /* Create new registered socket */
                    RegisteredSocket registeredSocket = new RegisteredSocket(
                            LLCP_CONNECTIONLESS_SOCKET_TYPE, sockeHandle, sap);

                    /* Put this socket into a list of registered socket */
                    mRegisteredSocketList.add(registeredSocket);
                }

                /* update socket handle generation */
                mGeneratedSocketHandle++;

                return sockeHandle;

            } else {
                /* No socket available */
                return ErrorCodes.ERROR_INSUFFICIENT_RESOURCES;
            }

        }

        @Override
        public int createLlcpServiceSocket(int sap, String sn, int miu, int rw, int linearBufferLength)
                throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            if (mNbSocketCreated < LLCP_SOCKET_NB_MAX) {
                int sockeHandle = mGeneratedSocketHandle;

                if (mLlcpLinkState == NfcAdapter.LLCP_LINK_STATE_ACTIVATED) {
                    NativeLlcpServiceSocket socket;

                    socket = mManager.doCreateLlcpServiceSocket(sap, sn, miu, rw, linearBufferLength);
                    if (socket != null) {
                        synchronized(NfcService.this) {
                            /* Update the number of socket created */
                            mNbSocketCreated++;
                            /* Add the socket into the socket map */
                            mSocketMap.put(sockeHandle, socket);
                        }
                    } else {
                        /* socket creation error - update the socket handle counter */
                        mGeneratedSocketHandle -= 1;

                        /* Get Error Status */
                        int errorStatus = mManager.doGetLastError();

                        switch (errorStatus) {
                            case ErrorCodes.ERROR_BUFFER_TO_SMALL:
                                return ErrorCodes.ERROR_BUFFER_TO_SMALL;
                            case ErrorCodes.ERROR_INSUFFICIENT_RESOURCES:
                                return ErrorCodes.ERROR_INSUFFICIENT_RESOURCES;
                            default:
                                return ErrorCodes.ERROR_SOCKET_CREATION;
                        }
                    }
                } else {

                    /* Check SAP is not already used */
                    if (!CheckSocketSap(sap)) {
                        return ErrorCodes.ERROR_SAP_USED;
                    }

                    /* Service Name */
                    if (!CheckSocketServiceName(sn)) {
                        return ErrorCodes.ERROR_SERVICE_NAME_USED;
                    }

                    /* Check socket options */
                    if (!CheckSocketOptions(miu, rw, linearBufferLength)) {
                        return ErrorCodes.ERROR_SOCKET_OPTIONS;
                    }

                    NativeLlcpServiceSocket socket = new NativeLlcpServiceSocket(sap, sn, miu, rw,
                            linearBufferLength);
                    synchronized(NfcService.this) {
                        /* Add the socket into the socket map */
                        mSocketMap.put(sockeHandle, socket);

                        /* Update the number of socket created */
                        mNbSocketCreated++;
                    }
                    /* Create new registered socket */
                    RegisteredSocket registeredSocket = new RegisteredSocket(LLCP_SERVICE_SOCKET_TYPE,
                            sockeHandle, sap, sn, miu, rw, linearBufferLength);

                    /* Put this socket into a list of registered socket */
                    mRegisteredSocketList.add(registeredSocket);
                }

                /* update socket handle generation */
                mGeneratedSocketHandle += 1;

                if (DBG) Log.d(TAG, "Llcp Service Socket Handle =" + sockeHandle);
                return sockeHandle;
            } else {
                /* No socket available */
                return ErrorCodes.ERROR_INSUFFICIENT_RESOURCES;
            }
        }

        @Override
        public int createLlcpSocket(int sap, int miu, int rw, int linearBufferLength)
                throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            if (mNbSocketCreated < LLCP_SOCKET_NB_MAX) {

                int sockeHandle = mGeneratedSocketHandle;

                if (mLlcpLinkState == NfcAdapter.LLCP_LINK_STATE_ACTIVATED) {
                    if (DBG) Log.d(TAG, "creating llcp socket while activated");
                    NativeLlcpSocket socket;

                    socket = mManager.doCreateLlcpSocket(sap, miu, rw, linearBufferLength);

                    if (socket != null) {
                        synchronized(NfcService.this) {
                            /* Update the number of socket created */
                            mNbSocketCreated++;
                            /* Add the socket into the socket map */
                            mSocketMap.put(sockeHandle, socket);
                        }
                    } else {
                        /*
                         * socket creation error - update the socket handle
                         * generation
                         */
                        mGeneratedSocketHandle -= 1;

                        /* Get Error Status */
                        int errorStatus = mManager.doGetLastError();

                        Log.d(TAG, "failed to create llcp socket: " + ErrorCodes.asString(errorStatus));

                        switch (errorStatus) {
                            case ErrorCodes.ERROR_BUFFER_TO_SMALL:
                                return ErrorCodes.ERROR_BUFFER_TO_SMALL;
                            case ErrorCodes.ERROR_INSUFFICIENT_RESOURCES:
                                return ErrorCodes.ERROR_INSUFFICIENT_RESOURCES;
                            default:
                                return ErrorCodes.ERROR_SOCKET_CREATION;
                        }
                    }
                } else {
                    if (DBG) Log.d(TAG, "registering llcp socket while not activated");

                    /* Check SAP is not already used */
                    if (!CheckSocketSap(sap)) {
                        return ErrorCodes.ERROR_SAP_USED;
                    }

                    /* Check Socket options */
                    if (!CheckSocketOptions(miu, rw, linearBufferLength)) {
                        return ErrorCodes.ERROR_SOCKET_OPTIONS;
                    }

                    NativeLlcpSocket socket = new NativeLlcpSocket(sap, miu, rw);
                    synchronized(NfcService.this) {
                        /* Add the socket into the socket map */
                        mSocketMap.put(sockeHandle, socket);

                        /* Update the number of socket created */
                        mNbSocketCreated++;
                    }
                    /* Create new registered socket */
                    RegisteredSocket registeredSocket = new RegisteredSocket(LLCP_SOCKET_TYPE,
                            sockeHandle, sap, miu, rw, linearBufferLength);

                    /* Put this socket into a list of registered socket */
                    mRegisteredSocketList.add(registeredSocket);
                }

                /* update socket handle generation */
                mGeneratedSocketHandle++;

                return sockeHandle;
            } else {
                /* No socket available */
                return ErrorCodes.ERROR_INSUFFICIENT_RESOURCES;
            }
        }

        @Override
        public int deselectSecureElement() throws RemoteException {
            mContext.enforceCallingOrSelfPermission(ADMIN_PERM, ADMIN_PERM_ERROR);

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            if (mSelectedSeId == 0) {
                return ErrorCodes.ERROR_NO_SE_CONNECTED;
            }

            mManager.doDeselectSecureElement(mSelectedSeId);
            mNfcSecureElementState = false;
            mSelectedSeId = 0;

            /* store preference */
            mPrefsEditor.putBoolean(PREF_SECURE_ELEMENT_ON, false);
            mPrefsEditor.putInt(PREF_SECURE_ELEMENT_ID, 0);
            mPrefsEditor.apply();

            return ErrorCodes.SUCCESS;
        }

        @Override
        public ILlcpConnectionlessSocket getLlcpConnectionlessInterface() throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);
            return mLlcpConnectionlessSocketService;
        }

        @Override
        public ILlcpSocket getLlcpInterface() throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);
            return mLlcpSocket;
        }

        @Override
        public ILlcpServiceSocket getLlcpServiceInterface() throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);
            return mLlcpServerSocketService;
        }

        @Override
        public INfcTag getNfcTagInterface() throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);
            return mNfcTagService;
        }

        @Override
        public IP2pInitiator getP2pInitiatorInterface() throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);
            return mP2pInitiatorService;
        }

        @Override
        public IP2pTarget getP2pTargetInterface() throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);
            return mP2pTargetService;
        }

        public INfcSecureElement getNfcSecureElementInterface() {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);
            return mSecureElementService;
        }

        @Override
        public String getProperties(String param) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            if (param == null) {
                return null;
            }

            if (param.equals(PROPERTY_LLCP_LTO_VALUE)) {
                return Integer.toString(mPrefs.getInt(PREF_LLCP_LTO, LLCP_LTO_DEFAULT));
            } else if (param.equals(PROPERTY_LLCP_MIU_VALUE)) {
                return Integer.toString(mPrefs.getInt(PREF_LLCP_MIU, LLCP_MIU_DEFAULT));
            } else if (param.equals(PROPERTY_LLCP_WKS_VALUE)) {
                return Integer.toString(mPrefs.getInt(PREF_LLCP_WKS, LLCP_WKS_DEFAULT));
            } else if (param.equals(PROPERTY_LLCP_OPT_VALUE)) {
                return Integer.toString(mPrefs.getInt(PREF_LLCP_OPT, LLCP_OPT_DEFAULT));
            } else if (param.equals(PROPERTY_NFC_DISCOVERY_A_VALUE)) {
                return Boolean.toString(mPrefs.getBoolean(PREF_DISCOVERY_A, DISCOVERY_A_DEFAULT));
            } else if (param.equals(PROPERTY_NFC_DISCOVERY_B_VALUE)) {
                return Boolean.toString(mPrefs.getBoolean(PREF_DISCOVERY_B, DISCOVERY_B_DEFAULT));
            } else if (param.equals(PROPERTY_NFC_DISCOVERY_F_VALUE)) {
                return Boolean.toString(mPrefs.getBoolean(PREF_DISCOVERY_F, DISCOVERY_F_DEFAULT));
            } else if (param.equals(PROPERTY_NFC_DISCOVERY_NFCIP_VALUE)) {
                return Boolean.toString(mPrefs.getBoolean(PREF_DISCOVERY_NFCIP, DISCOVERY_NFCIP_DEFAULT));
            } else if (param.equals(PROPERTY_NFC_DISCOVERY_15693_VALUE)) {
                return Boolean.toString(mPrefs.getBoolean(PREF_DISCOVERY_15693, DISCOVERY_15693_DEFAULT));
            } else {
                return "Unknown property";
            }
        }

        @Override
        public int[] getSecureElementList() throws RemoteException {
            mContext.enforceCallingOrSelfPermission(ADMIN_PERM, ADMIN_PERM_ERROR);

            int[] list = null;
            if (mIsNfcEnabled == true) {
                list = mManager.doGetSecureElementList();
            }
            return list;
        }

        @Override
        public int getSelectedSecureElement() throws RemoteException {
            mContext.enforceCallingOrSelfPermission(ADMIN_PERM, ADMIN_PERM_ERROR);

            return mSelectedSeId;
        }

        @Override
        public boolean isEnabled() throws RemoteException {
            return mIsNfcEnabled;
        }

        @Override
        public void openTagConnection(Tag tag) throws RemoteException {
            // TODO: Remove obsolete code
        }

        @Override
        public int selectSecureElement(int seId) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(ADMIN_PERM, ADMIN_PERM_ERROR);

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            if (mSelectedSeId == seId) {
                return ErrorCodes.ERROR_SE_ALREADY_SELECTED;
            }

            if (mSelectedSeId != 0) {
                return ErrorCodes.ERROR_SE_CONNECTED;
            }

            mSelectedSeId = seId;
            mManager.doSelectSecureElement(mSelectedSeId);

            /* store */
            mPrefsEditor.putBoolean(PREF_SECURE_ELEMENT_ON, true);
            mPrefsEditor.putInt(PREF_SECURE_ELEMENT_ID, mSelectedSeId);
            mPrefsEditor.apply();

            mNfcSecureElementState = true;

            return ErrorCodes.SUCCESS;

        }

        @Override
        public int setProperties(String param, String value) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(ADMIN_PERM, ADMIN_PERM_ERROR);

            if (isEnabled()) {
                return ErrorCodes.ERROR_NFC_ON;
            }

            int val;

            /* Check params validity */
            if (param == null || value == null) {
                return ErrorCodes.ERROR_INVALID_PARAM;
            }

            if (param.equals(PROPERTY_LLCP_LTO_VALUE)) {
                val = Integer.parseInt(value);

                /* Check params */
                if (val > LLCP_LTO_MAX)
                    return ErrorCodes.ERROR_INVALID_PARAM;

                /* Store value */
                mPrefsEditor.putInt(PREF_LLCP_LTO, val);
                mPrefsEditor.apply();

                /* Update JNI */
                mManager.doSetProperties(PROPERTY_LLCP_LTO, val);

            } else if (param.equals(PROPERTY_LLCP_MIU_VALUE)) {
                val = Integer.parseInt(value);

                /* Check params */
                if ((val < LLCP_MIU_DEFAULT) || (val > LLCP_MIU_MAX))
                    return ErrorCodes.ERROR_INVALID_PARAM;

                /* Store value */
                mPrefsEditor.putInt(PREF_LLCP_MIU, val);
                mPrefsEditor.apply();

                /* Update JNI */
                mManager.doSetProperties(PROPERTY_LLCP_MIU, val);

            } else if (param.equals(PROPERTY_LLCP_WKS_VALUE)) {
                val = Integer.parseInt(value);

                /* Check params */
                if (val > LLCP_WKS_MAX)
                    return ErrorCodes.ERROR_INVALID_PARAM;

                /* Store value */
                mPrefsEditor.putInt(PREF_LLCP_WKS, val);
                mPrefsEditor.apply();

                /* Update JNI */
                mManager.doSetProperties(PROPERTY_LLCP_WKS, val);

            } else if (param.equals(PROPERTY_LLCP_OPT_VALUE)) {
                val = Integer.parseInt(value);

                /* Check params */
                if (val > LLCP_OPT_MAX)
                    return ErrorCodes.ERROR_INVALID_PARAM;

                /* Store value */
                mPrefsEditor.putInt(PREF_LLCP_OPT, val);
                mPrefsEditor.apply();

                /* Update JNI */
                mManager.doSetProperties(PROPERTY_LLCP_OPT, val);

            } else if (param.equals(PROPERTY_NFC_DISCOVERY_A_VALUE)) {
                boolean b = Boolean.parseBoolean(value);

                /* Store value */
                mPrefsEditor.putBoolean(PREF_DISCOVERY_A, b);
                mPrefsEditor.apply();

                /* Update JNI */
                mManager.doSetProperties(PROPERTY_NFC_DISCOVERY_A, b ? 1 : 0);

            } else if (param.equals(PROPERTY_NFC_DISCOVERY_B_VALUE)) {
                boolean b = Boolean.parseBoolean(value);

                /* Store value */
                mPrefsEditor.putBoolean(PREF_DISCOVERY_B, b);
                mPrefsEditor.apply();

                /* Update JNI */
                mManager.doSetProperties(PROPERTY_NFC_DISCOVERY_B, b ? 1 : 0);

            } else if (param.equals(PROPERTY_NFC_DISCOVERY_F_VALUE)) {
                boolean b = Boolean.parseBoolean(value);

                /* Store value */
                mPrefsEditor.putBoolean(PREF_DISCOVERY_F, b);
                mPrefsEditor.apply();

                /* Update JNI */
                mManager.doSetProperties(PROPERTY_NFC_DISCOVERY_F, b ? 1 : 0);

            } else if (param.equals(PROPERTY_NFC_DISCOVERY_15693_VALUE)) {
                boolean b = Boolean.parseBoolean(value);

                /* Store value */
                mPrefsEditor.putBoolean(PREF_DISCOVERY_15693, b);
                mPrefsEditor.apply();

                /* Update JNI */
                mManager.doSetProperties(PROPERTY_NFC_DISCOVERY_15693, b ? 1 : 0);

            } else if (param.equals(PROPERTY_NFC_DISCOVERY_NFCIP_VALUE)) {
                boolean b = Boolean.parseBoolean(value);

                /* Store value */
                mPrefsEditor.putBoolean(PREF_DISCOVERY_NFCIP, b);
                mPrefsEditor.apply();

                /* Update JNI */
                mManager.doSetProperties(PROPERTY_NFC_DISCOVERY_NFCIP, b ? 1 : 0);

            } else {
                return ErrorCodes.ERROR_INVALID_PARAM;
            }

            return ErrorCodes.SUCCESS;
        }

        @Override
        public NdefMessage localGet() throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            synchronized (this) {
                return mLocalMessage;
            }
        }

        @Override
        public void localSet(NdefMessage message) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(ADMIN_PERM, ADMIN_PERM_ERROR);

            synchronized (this) {
                mLocalMessage = message;
                Context context = NfcService.this.getApplicationContext();

                // Send a message to the UI thread to show or hide the icon so the requests are
                // serialized and the icon can't get out of sync with reality.
                if (message != null) {
                    FileOutputStream out = null;

                    try {
                        out = context.openFileOutput(MY_TAG_FILE_NAME, Context.MODE_PRIVATE);
                        byte[] bytes = message.toByteArray();
                        if (bytes.length == 0) {
                            Log.w(TAG, "Setting a empty mytag");
                        }

                        out.write(bytes);
                    } catch (IOException e) {
                        Log.e(TAG, "Could not write mytag file", e);
                    } finally {
                        try {
                            if (out != null) {
                                out.flush();
                                out.close();
                            }
                        } catch (IOException e) {
                            // Ignore
                        }
                    }

                    // Only show the icon if NFC is enabled.
                    if (mIsNfcEnabled) {
                        sendMessage(MSG_SHOW_MY_TAG_ICON, null);
                    }
                } else {
                    context.deleteFile(MY_TAG_FILE_NAME);
                    sendMessage(MSG_HIDE_MY_TAG_ICON, null);
                }
            }
        }
    };

    private final ILlcpSocket mLlcpSocket = new ILlcpSocket.Stub() {

        private final int CONNECT_FLAG = 0x01;
        private final int CLOSE_FLAG   = 0x02;
        private final int RECV_FLAG    = 0x04;
        private final int SEND_FLAG    = 0x08;

        private int concurrencyFlags;
        private Object sync;

        @Override
        public int close(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeLlcpSocket socket = null;
            boolean isSuccess = false;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the socket in the hmap */
            socket = (NativeLlcpSocket) findSocket(nativeHandle);
            if (socket != null) {
                if (mLlcpLinkState == NfcAdapter.LLCP_LINK_STATE_ACTIVATED) {
                    isSuccess = socket.doClose();
                    if (isSuccess) {
                        /* Remove the socket closed from the hmap */
                        RemoveSocket(nativeHandle);
                        /* Update mNbSocketCreated */
                        mNbSocketCreated--;
                        return ErrorCodes.SUCCESS;
                    } else {
                        return ErrorCodes.ERROR_IO;
                    }
                } else {
                    /* Remove the socket closed from the hmap */
                    RemoveSocket(nativeHandle);

                    /* Remove registered socket from the list */
                    RemoveRegisteredSocket(nativeHandle);

                    /* Update mNbSocketCreated */
                    mNbSocketCreated--;

                    return ErrorCodes.SUCCESS;
                }
            } else {
                return ErrorCodes.ERROR_IO;
            }
        }

        @Override
        public int connect(int nativeHandle, int sap) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeLlcpSocket socket = null;
            boolean isSuccess = false;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the socket in the hmap */
            socket = (NativeLlcpSocket) findSocket(nativeHandle);
            if (socket != null) {
                isSuccess = socket.doConnect(sap);
                if (isSuccess) {
                    return ErrorCodes.SUCCESS;
                } else {
                    return ErrorCodes.ERROR_IO;
                }
            } else {
                return ErrorCodes.ERROR_IO;
            }

        }

        @Override
        public int connectByName(int nativeHandle, String sn) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeLlcpSocket socket = null;
            boolean isSuccess = false;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the socket in the hmap */
            socket = (NativeLlcpSocket) findSocket(nativeHandle);
            if (socket != null) {
                isSuccess = socket.doConnectBy(sn);
                if (isSuccess) {
                    return ErrorCodes.SUCCESS;
                } else {
                    return ErrorCodes.ERROR_IO;
                }
            } else {
                return ErrorCodes.ERROR_IO;
            }

        }

        @Override
        public int getLocalSap(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeLlcpSocket socket = null;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the socket in the hmap */
            socket = (NativeLlcpSocket) findSocket(nativeHandle);
            if (socket != null) {
                return socket.getSap();
            } else {
                return 0;
            }
        }

        @Override
        public int getLocalSocketMiu(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeLlcpSocket socket = null;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the socket in the hmap */
            socket = (NativeLlcpSocket) findSocket(nativeHandle);
            if (socket != null) {
                return socket.getMiu();
            } else {
                return 0;
            }
        }

        @Override
        public int getLocalSocketRw(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeLlcpSocket socket = null;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the socket in the hmap */
            socket = (NativeLlcpSocket) findSocket(nativeHandle);
            if (socket != null) {
                return socket.getRw();
            } else {
                return 0;
            }
        }

        @Override
        public int getRemoteSocketMiu(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeLlcpSocket socket = null;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the socket in the hmap */
            socket = (NativeLlcpSocket) findSocket(nativeHandle);
            if (socket != null) {
                if (socket.doGetRemoteSocketMiu() != 0) {
                    return socket.doGetRemoteSocketMiu();
                } else {
                    return ErrorCodes.ERROR_SOCKET_NOT_CONNECTED;
                }
            } else {
                return ErrorCodes.ERROR_SOCKET_NOT_CONNECTED;
            }
        }

        @Override
        public int getRemoteSocketRw(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeLlcpSocket socket = null;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the socket in the hmap */
            socket = (NativeLlcpSocket) findSocket(nativeHandle);
            if (socket != null) {
                if (socket.doGetRemoteSocketRw() != 0) {
                    return socket.doGetRemoteSocketRw();
                } else {
                    return ErrorCodes.ERROR_SOCKET_NOT_CONNECTED;
                }
            } else {
                return ErrorCodes.ERROR_SOCKET_NOT_CONNECTED;
            }
        }

        @Override
        public int receive(int nativeHandle, byte[] receiveBuffer) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeLlcpSocket socket = null;
            int receiveLength = 0;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the socket in the hmap */
            socket = (NativeLlcpSocket) findSocket(nativeHandle);
            if (socket != null) {
                return socket.doReceive(receiveBuffer);
            } else {
                return 0;
            }
        }

        @Override
        public int send(int nativeHandle, byte[] data) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeLlcpSocket socket = null;
            boolean isSuccess = false;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the socket in the hmap */
            socket = (NativeLlcpSocket) findSocket(nativeHandle);
            if (socket != null) {
                isSuccess = socket.doSend(data);
                if (isSuccess) {
                    return ErrorCodes.SUCCESS;
                } else {
                    return ErrorCodes.ERROR_IO;
                }
            } else {
                return ErrorCodes.ERROR_IO;
            }
        }
    };

    private final ILlcpServiceSocket mLlcpServerSocketService = new ILlcpServiceSocket.Stub() {

        @Override
        public int accept(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeLlcpServiceSocket socket = null;
            NativeLlcpSocket clientSocket = null;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            if (mNbSocketCreated < LLCP_SOCKET_NB_MAX) {
                /* find the socket in the hmap */
                socket = (NativeLlcpServiceSocket) findSocket(nativeHandle);
                if (socket != null) {
                    clientSocket = socket.doAccept(socket.getMiu(),
                            socket.getRw(), socket.getLinearBufferLength());
                    if (clientSocket != null) {
                        /* Add the socket into the socket map */
                        synchronized(this) {
                            mSocketMap.put(clientSocket.getHandle(), clientSocket);
                            mNbSocketCreated++;
                        }
                        return clientSocket.getHandle();
                    } else {
                        return ErrorCodes.ERROR_IO;
                    }
                } else {
                    return ErrorCodes.ERROR_IO;
                }
            } else {
                return ErrorCodes.ERROR_INSUFFICIENT_RESOURCES;
            }

        }

        @Override
        public void close(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeLlcpServiceSocket socket = null;
            boolean isSuccess = false;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return;
            }

            /* find the socket in the hmap */
            boolean closed = false;
            socket = (NativeLlcpServiceSocket) findSocket(nativeHandle);
            if (socket != null) {
                if (mLlcpLinkState == NfcAdapter.LLCP_LINK_STATE_ACTIVATED) {
                    isSuccess = socket.doClose();
                    if (isSuccess) {
                        closed = true;
                    }
                } else {
                    closed = true;
                }
            }

            // If the socket is closed remove it from the socket lists
            if (closed) {
                synchronized (this) {
                    /* Remove the socket closed from the hmap */
                    RemoveSocket(nativeHandle);

                    /* Update mNbSocketCreated */
                    mNbSocketCreated--;

                    /* Remove registered socket from the list */
                    RemoveRegisteredSocket(nativeHandle);
                }
            }
        }
    };

    private final ILlcpConnectionlessSocket mLlcpConnectionlessSocketService = new ILlcpConnectionlessSocket.Stub() {

        @Override
        public void close(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeLlcpConnectionlessSocket socket = null;
            boolean isSuccess = false;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return;
            }

            /* find the socket in the hmap */
            socket = (NativeLlcpConnectionlessSocket) findSocket(nativeHandle);
            if (socket != null) {
                if (mLlcpLinkState == NfcAdapter.LLCP_LINK_STATE_ACTIVATED) {
                    isSuccess = socket.doClose();
                    if (isSuccess) {
                        /* Remove the socket closed from the hmap */
                        RemoveSocket(nativeHandle);
                        /* Update mNbSocketCreated */
                        mNbSocketCreated--;
                    }
                } else {
                    /* Remove the socket closed from the hmap */
                    RemoveSocket(nativeHandle);

                    /* Remove registered socket from the list */
                    RemoveRegisteredSocket(nativeHandle);

                    /* Update mNbSocketCreated */
                    mNbSocketCreated--;
                }
            }
        }

        @Override
        public int getSap(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeLlcpConnectionlessSocket socket = null;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the socket in the hmap */
            socket = (NativeLlcpConnectionlessSocket) findSocket(nativeHandle);
            if (socket != null) {
                return socket.getSap();
            } else {
                return 0;
            }
        }

        @Override
        public LlcpPacket receiveFrom(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeLlcpConnectionlessSocket socket = null;
            LlcpPacket packet;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return null;
            }

            /* find the socket in the hmap */
            socket = (NativeLlcpConnectionlessSocket) findSocket(nativeHandle);
            if (socket != null) {
                packet = socket.doReceiveFrom(socket.getLinkMiu());
                if (packet != null) {
                    return packet;
                }
                return null;
            } else {
                return null;
            }
        }

        @Override
        public int sendTo(int nativeHandle, LlcpPacket packet) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeLlcpConnectionlessSocket socket = null;
            boolean isSuccess = false;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the socket in the hmap */
            socket = (NativeLlcpConnectionlessSocket) findSocket(nativeHandle);
            if (socket != null) {
                isSuccess = socket.doSendTo(packet.getRemoteSap(), packet.getDataBuffer());
                if (isSuccess) {
                    return ErrorCodes.SUCCESS;
                } else {
                    return ErrorCodes.ERROR_IO;
                }
            } else {
                return ErrorCodes.ERROR_IO;
            }
        }
    };

    private final INfcTag mNfcTagService = new INfcTag.Stub() {

        @Override
        public int close(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeNfcTag tag = null;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the tag in the hmap */
            tag = (NativeNfcTag) findObject(nativeHandle);
            if (tag != null) {
                /* Remove the device from the hmap */
                unregisterObject(nativeHandle);
                tag.disconnect();
                return ErrorCodes.SUCCESS;
            }
            /* Restart polling loop for notification */
            maybeEnableDiscovery();
            return ErrorCodes.ERROR_DISCONNECT;
        }

        @Override
        public int connect(int nativeHandle, int technology) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeNfcTag tag = null;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the tag in the hmap */
            tag = (NativeNfcTag) findObject(nativeHandle);
            if (tag == null) {
                return ErrorCodes.ERROR_DISCONNECT;
            }

            // Note that on most tags, all technologies are behind a single
            // handle. This means that the connect at the lower levels
            // will do nothing, as the tag is already connected to that handle.
            if (tag.connect(technology)) {
                return ErrorCodes.SUCCESS;
            } else {
                return ErrorCodes.ERROR_DISCONNECT;
            }
        }

        @Override
        public int reconnect(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeNfcTag tag = null;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the tag in the hmap */
            tag = (NativeNfcTag) findObject(nativeHandle);
            if (tag != null) {
                if (tag.reconnect()) {
                    return ErrorCodes.SUCCESS;
                } else {
                    return ErrorCodes.ERROR_DISCONNECT;
                }
            }
            return ErrorCodes.ERROR_DISCONNECT;
        }

        @Override
        public int[] getTechList(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return null;
            }

            /* find the tag in the hmap */
            NativeNfcTag tag = (NativeNfcTag) findObject(nativeHandle);
            if (tag != null) {
                return tag.getTechList();
            }
            return null;
        }

        @Override
        public byte[] getUid(int nativeHandle) throws RemoteException {
            NativeNfcTag tag = null;
            byte[] uid;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return null;
            }

            /* find the tag in the hmap */
            tag = (NativeNfcTag) findObject(nativeHandle);
            if (tag != null) {
                uid = tag.getUid();
                return uid;
            }
            return null;
        }

        @Override
        public boolean isPresent(int nativeHandle) throws RemoteException {
            NativeNfcTag tag = null;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return false;
            }

            /* find the tag in the hmap */
            tag = (NativeNfcTag) findObject(nativeHandle);
            if (tag == null) {
                return false;
            }

            return tag.presenceCheck();
        }

        @Override
        public boolean isNdef(int nativeHandle) throws RemoteException {
            NativeNfcTag tag = null;
            boolean isSuccess = false;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return isSuccess;
            }

            /* find the tag in the hmap */
            tag = (NativeNfcTag) findObject(nativeHandle);
            int[] ndefInfo = new int[2];
            if (tag != null) {
                isSuccess = tag.checkNdef(ndefInfo);
            }
            return isSuccess;
        }

        @Override
        public byte[] transceive(int nativeHandle, byte[] data, boolean raw)
                throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeNfcTag tag = null;
            byte[] response;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return null;
            }

            /* find the tag in the hmap */
            tag = (NativeNfcTag) findObject(nativeHandle);
            if (tag != null) {
                response = tag.transceive(data, raw);
                return response;
            }
            return null;
        }

        @Override
        public NdefMessage ndefRead(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeNfcTag tag;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return null;
            }

            /* find the tag in the hmap */
            tag = (NativeNfcTag) findObject(nativeHandle);
            if (tag != null) {
                byte[] buf = tag.read();
                if (buf == null)
                    return null;

                /* Create an NdefMessage */
                try {
                    return new NdefMessage(buf);
                } catch (FormatException e) {
                    return null;
                }
            }
            return null;
        }

        @Override
        public int ndefWrite(int nativeHandle, NdefMessage msg) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeNfcTag tag;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the tag in the hmap */
            tag = (NativeNfcTag) findObject(nativeHandle);
            if (tag == null) {
                return ErrorCodes.ERROR_IO;
            }

            if (tag.write(msg.toByteArray())) {
                return ErrorCodes.SUCCESS;
            }
            else {
                return ErrorCodes.ERROR_IO;
            }

        }

        @Override
        public int getLastError(int nativeHandle) throws RemoteException {
            return(mManager.doGetLastError());
        }

        @Override
        public boolean ndefIsWritable(int nativeHandle) throws RemoteException {
            throw new UnsupportedOperationException();
        }

        @Override
        public int ndefMakeReadOnly(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeNfcTag tag;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the tag in the hmap */
            tag = (NativeNfcTag) findObject(nativeHandle);
            if (tag == null) {
                return ErrorCodes.ERROR_IO;
            }

            if (tag.makeReadonly()) {
                return ErrorCodes.SUCCESS;
            }
            else {
                return ErrorCodes.ERROR_IO;
            }
        }

        @Override
        public int formatNdef(int nativeHandle, byte[] key) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeNfcTag tag;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the tag in the hmap */
            tag = (NativeNfcTag) findObject(nativeHandle);
            if (tag == null) {
                return ErrorCodes.ERROR_IO;
            }

            if (tag.formatNdef(key)) {
                return ErrorCodes.SUCCESS;
            }
            else {
                return ErrorCodes.ERROR_IO;
            }
        }


    };

    private final IP2pInitiator mP2pInitiatorService = new IP2pInitiator.Stub() {

        @Override
        public byte[] getGeneralBytes(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeP2pDevice device;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return null;
            }

            /* find the device in the hmap */
            device = (NativeP2pDevice) findObject(nativeHandle);
            if (device != null) {
                byte[] buff = device.getGeneralBytes();
                if (buff == null)
                    return null;
                return buff;
            }
            return null;
        }

        @Override
        public int getMode(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeP2pDevice device;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the device in the hmap */
            device = (NativeP2pDevice) findObject(nativeHandle);
            if (device != null) {
                return device.getMode();
            }
            return ErrorCodes.ERROR_INVALID_PARAM;
        }

        @Override
        public byte[] receive(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeP2pDevice device;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return null;
            }

            /* find the device in the hmap */
            device = (NativeP2pDevice) findObject(nativeHandle);
            if (device != null) {
                byte[] buff = device.doReceive();
                if (buff == null)
                    return null;
                return buff;
            }
            /* Restart polling loop for notification */
            maybeEnableDiscovery();
            return null;
        }

        @Override
        public boolean send(int nativeHandle, byte[] data) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeP2pDevice device;
            boolean isSuccess = false;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return isSuccess;
            }

            /* find the device in the hmap */
            device = (NativeP2pDevice) findObject(nativeHandle);
            if (device != null) {
                isSuccess = device.doSend(data);
            }
            return isSuccess;
        }
    };

    private final IP2pTarget mP2pTargetService = new IP2pTarget.Stub() {

        @Override
        public int connect(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeP2pDevice device;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the device in the hmap */
            device = (NativeP2pDevice) findObject(nativeHandle);
            if (device != null) {
                if (device.doConnect()) {
                    return ErrorCodes.SUCCESS;
                }
            }
            return ErrorCodes.ERROR_CONNECT;
        }

        @Override
        public boolean disconnect(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeP2pDevice device;
            boolean isSuccess = false;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return isSuccess;
            }

            /* find the device in the hmap */
            device = (NativeP2pDevice) findObject(nativeHandle);
            if (device != null) {
                if (isSuccess = device.doDisconnect()) {
                    /* remove the device from the hmap */
                    unregisterObject(nativeHandle);
                    /* Restart polling loop for notification */
                    maybeEnableDiscovery();
                }
            }
            return isSuccess;

        }

        @Override
        public byte[] getGeneralBytes(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeP2pDevice device;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return null;
            }

            /* find the device in the hmap */
            device = (NativeP2pDevice) findObject(nativeHandle);
            if (device != null) {
                byte[] buff = device.getGeneralBytes();
                if (buff == null)
                    return null;
                return buff;
            }
            return null;
        }

        @Override
        public int getMode(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeP2pDevice device;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the device in the hmap */
            device = (NativeP2pDevice) findObject(nativeHandle);
            if (device != null) {
                return device.getMode();
            }
            return ErrorCodes.ERROR_INVALID_PARAM;
        }

        @Override
        public byte[] transceive(int nativeHandle, byte[] data)
                throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeP2pDevice device;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return null;
            }

            /* find the device in the hmap */
            device = (NativeP2pDevice) findObject(nativeHandle);
            if (device != null) {
                byte[] buff = device.doTransceive(data);
                if (buff == null)
                    return null;
                return buff;
            }
            return null;
        }
    };

    private INfcSecureElement mSecureElementService = new INfcSecureElement.Stub() {

        public int openSecureElementConnection() throws RemoteException {
            Log.d(TAG, "openSecureElementConnection");
            int handle;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return 0;
            }

            // Check in an open is already pending
            if (mOpenSmxPending) {
                return 0;
            }

            handle = mSecureElement.doOpenSecureElementConnection();

            if (handle == 0) {
                mOpenSmxPending = false;
            } else {
                mSecureElementHandle = handle;

                /* Start timer */
                mTimerOpenSmx = new Timer();
                mTimerOpenSmx.schedule(new TimerOpenSecureElement(), 30000);

                /* Update state */
                isOpened = true;
                isClosed = false;
                mOpenSmxPending = true;
            }

            return handle;
        }

        public int closeSecureElementConnection(int nativeHandle)
                throws RemoteException {

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            // Check if the SE connection is closed
            if (isClosed) {
                return -1;
            }

            // Check if the SE connection is opened
            if (!isOpened) {
                return -1;
            }

            if (mSecureElement.doDisconnect(nativeHandle)) {

                /* Stop timer */
                mTimerOpenSmx.cancel();

                /* Restart polling loop for notification */
                mManager.enableDiscovery(DISCOVERY_MODE_READER);

                /* Update state */
                isOpened = false;
                isClosed = true;
                mOpenSmxPending = false;

                return ErrorCodes.SUCCESS;
            } else {

                /* Stop timer */
                mTimerOpenSmx.cancel();

                /* Restart polling loop for notification */
                mManager.enableDiscovery(DISCOVERY_MODE_READER);

                /* Update state */
                isOpened = false;
                isClosed = true;
                mOpenSmxPending = false;

                return ErrorCodes.ERROR_DISCONNECT;
            }
        }

        public int[] getSecureElementTechList(int nativeHandle)
                throws RemoteException {
            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return null;
            }

            // Check if the SE connection is closed
            if (isClosed) {
                return null;
            }

            // Check if the SE connection is opened
            if (!isOpened) {
                return null;
            }

            int[] techList = mSecureElement.doGetTechList(nativeHandle);

            /* Stop and Restart timer */
            mTimerOpenSmx.cancel();
            mTimerOpenSmx = new Timer();
            mTimerOpenSmx.schedule(new TimerOpenSecureElement(), 30000);

            return techList;
        }

        public byte[] getSecureElementUid(int nativeHandle)
                throws RemoteException {
            byte[] uid;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return null;
            }

            // Check if the SE connection is closed
            if (isClosed) {
                return null;
            }

            // Check if the SE connection is opened
            if (!isOpened) {
                return null;
            }

            uid = mSecureElement.doGetUid(nativeHandle);

            /* Stop and Restart timer */
            mTimerOpenSmx.cancel();
            mTimerOpenSmx = new Timer();
            mTimerOpenSmx.schedule(new TimerOpenSecureElement(), 30000);

            return uid;
        }

        public byte[] exchangeAPDU(int nativeHandle, byte[] data)
                throws RemoteException {
            byte[] response;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return null;
            }

            // Check if the SE connection is closed
            if (isClosed) {
                return null;
            }

            // Check if the SE connection is opened
            if (!isOpened) {
                return null;
            }

            response = mSecureElement.doTransceive(nativeHandle, data);

            /* Stop and Restart timer */
            mTimerOpenSmx.cancel();
            mTimerOpenSmx = new Timer();
            mTimerOpenSmx.schedule(new TimerOpenSecureElement(), 30000);

            return response;

        }
    };

    class TimerOpenSecureElement extends TimerTask {

        @Override
        public void run() {
            if (mSecureElementHandle != 0) {
                Log.d(TAG, "Open SMX timer expired");
                try {
                    mSecureElementService
                            .closeSecureElementConnection(mSecureElementHandle);
                } catch (RemoteException e) {
                }
            }

        }

    }

    private boolean _enable(boolean oldEnabledState) {
        boolean isSuccess = mManager.initialize();
        if (isSuccess) {
            applyProperties();

            /* Check Secure Element setting */
            mNfcSecureElementState = mPrefs.getBoolean(PREF_SECURE_ELEMENT_ON,
                    SECURE_ELEMENT_ON_DEFAULT);

            if (mNfcSecureElementState) {
                int secureElementId = mPrefs.getInt(PREF_SECURE_ELEMENT_ID,
                        SECURE_ELEMENT_ID_DEFAULT);
                int[] Se_list = mManager.doGetSecureElementList();
                if (Se_list != null) {
                    for (int i = 0; i < Se_list.length; i++) {
                        if (Se_list[i] == secureElementId) {
                            mManager.doSelectSecureElement(Se_list[i]);
                            mSelectedSeId = Se_list[i];
                            break;
                        }
                    }
                }
            }

            mIsNfcEnabled = true;

            /* Start polling loop */
            maybeEnableDiscovery();

            /* bring up the my tag server */
            mMyTagServer.start();

        } else {
            mIsNfcEnabled = false;
        }

        updateNfcOnSetting(oldEnabledState);

        return isSuccess;
    }

    /** Enable active tag discovery if screen is on and NFC is enabled */
    private synchronized void maybeEnableDiscovery() {
        if (mScreenOn && mIsNfcEnabled) {
            mManager.enableDiscovery(DISCOVERY_MODE_READER);
        }
    }

    /** Disable active tag discovery if necessary */
    private synchronized void maybeDisableDiscovery() {
        if (mIsNfcEnabled) {
            mManager.disableDiscovery();
        }
    }

    private void applyProperties() {
        mManager.doSetProperties(PROPERTY_LLCP_LTO, mPrefs.getInt(PREF_LLCP_LTO, LLCP_LTO_DEFAULT));
        mManager.doSetProperties(PROPERTY_LLCP_MIU, mPrefs.getInt(PREF_LLCP_MIU, LLCP_MIU_DEFAULT));
        mManager.doSetProperties(PROPERTY_LLCP_WKS, mPrefs.getInt(PREF_LLCP_WKS, LLCP_WKS_DEFAULT));
        mManager.doSetProperties(PROPERTY_LLCP_OPT, mPrefs.getInt(PREF_LLCP_OPT, LLCP_OPT_DEFAULT));
        mManager.doSetProperties(PROPERTY_NFC_DISCOVERY_A,
                mPrefs.getBoolean(PREF_DISCOVERY_A, DISCOVERY_A_DEFAULT) ? 1 : 0);
        mManager.doSetProperties(PROPERTY_NFC_DISCOVERY_B,
                mPrefs.getBoolean(PREF_DISCOVERY_B, DISCOVERY_B_DEFAULT) ? 1 : 0);
        mManager.doSetProperties(PROPERTY_NFC_DISCOVERY_F,
                mPrefs.getBoolean(PREF_DISCOVERY_F, DISCOVERY_F_DEFAULT) ? 1 : 0);
        mManager.doSetProperties(PROPERTY_NFC_DISCOVERY_15693,
                mPrefs.getBoolean(PREF_DISCOVERY_15693, DISCOVERY_15693_DEFAULT) ? 1 : 0);
        mManager.doSetProperties(PROPERTY_NFC_DISCOVERY_NFCIP,
                mPrefs.getBoolean(PREF_DISCOVERY_NFCIP, DISCOVERY_NFCIP_DEFAULT) ? 1 : 0);
     }

    private void updateNfcOnSetting(boolean oldEnabledState) {
        int state;

        mPrefsEditor.putBoolean(PREF_NFC_ON, mIsNfcEnabled);
        mPrefsEditor.apply();

        synchronized(this) {
            if (oldEnabledState != mIsNfcEnabled) {
                Intent intent = new Intent(NfcAdapter.ACTION_ADAPTER_STATE_CHANGE);
                intent.setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
                intent.putExtra(NfcAdapter.EXTRA_NEW_BOOLEAN_STATE, mIsNfcEnabled);
                mContext.sendBroadcast(intent);
            }

            if (mIsNfcEnabled) {

                Context context = getApplicationContext();

                // Set this to null by default. If there isn't a tag on disk
                // or if there was an error reading the tag then this will cause
                // the status bar icon to be removed.
                NdefMessage myTag = null;

                FileInputStream input = null;

                try {
                    input = context.openFileInput(MY_TAG_FILE_NAME);
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();

                    byte[] buffer = new byte[4096];
                    int read = 0;
                    while ((read = input.read(buffer)) > 0) {
                        bytes.write(buffer, 0, read);
                    }

                    myTag = new NdefMessage(bytes.toByteArray());
                } catch (FileNotFoundException e) {
                    // Ignore.
                } catch (IOException e) {
                    Log.e(TAG, "Could not read mytag file: ", e);
                    context.deleteFile(MY_TAG_FILE_NAME);
                } catch (FormatException e) {
                    Log.e(TAG, "Invalid NdefMessage for mytag", e);
                    context.deleteFile(MY_TAG_FILE_NAME);
                } finally {
                    try {
                        if (input != null) {
                            input.close();
                        }
                    } catch (IOException e) {
                        // Ignore
                    }
                }

                try {
                    mNfcAdapter.localSet(myTag);
                } catch (RemoteException e) {
                    // Ignore
                }
            } else {
                sendMessage(MSG_HIDE_MY_TAG_ICON, null);
            }
        }
    }

    // Reset all internals
    private synchronized void reset() {
        // TODO: none of these appear to be synchronized but are
        // read/written from different threads (notably Binder threads)...

        // Clear tables
        mObjectMap.clear();
        mSocketMap.clear();
        mRegisteredSocketList.clear();

        // Reset variables
        mLlcpLinkState = NfcAdapter.LLCP_LINK_STATE_DEACTIVATED;
        mNbSocketCreated = 0;
        mIsNfcEnabled = false;
        mSelectedSeId = 0;
    }

    private synchronized Object findObject(int key) {
        Object device = null;

        device = mObjectMap.get(key);
        if (device == null) {
            Log.w(TAG, "Handle not found !");
        }

        return device;
    }

    synchronized void registerTagObject(NativeNfcTag nativeTag) {
        mObjectMap.put(nativeTag.getHandle(), nativeTag);
    }

    synchronized void unregisterObject(int handle) {
        mObjectMap.remove(handle);
    }

    private synchronized Object findSocket(int key) {
        Object socket = null;

        socket = mSocketMap.get(key);

        return socket;
    }

    private void RemoveSocket(int key) {
        mSocketMap.remove(key);
    }

    private boolean CheckSocketSap(int sap) {
        /* List of sockets registered */
        ListIterator<RegisteredSocket> it = mRegisteredSocketList.listIterator();

        while (it.hasNext()) {
            RegisteredSocket registeredSocket = it.next();

            if (sap == registeredSocket.mSap) {
                /* SAP already used */
                return false;
            }
        }
        return true;
    }

    private boolean CheckSocketOptions(int miu, int rw, int linearBufferlength) {

        if (rw > LLCP_RW_MAX_VALUE || miu < LLCP_MIU_DEFAULT || linearBufferlength < miu) {
            return false;
        }
        return true;
    }

    private boolean CheckSocketServiceName(String sn) {

        /* List of sockets registered */
        ListIterator<RegisteredSocket> it = mRegisteredSocketList.listIterator();

        while (it.hasNext()) {
            RegisteredSocket registeredSocket = it.next();

            if (sn.equals(registeredSocket.mServiceName)) {
                /* Service Name already used */
                return false;
            }
        }
        return true;
    }

    private void RemoveRegisteredSocket(int nativeHandle) {
        /* check if sockets are registered */
        ListIterator<RegisteredSocket> it = mRegisteredSocketList.listIterator();

        while (it.hasNext()) {
            RegisteredSocket registeredSocket = it.next();
            if (registeredSocket.mHandle == nativeHandle) {
                /* remove the registered socket from the list */
                it.remove();
                if (DBG) Log.d(TAG, "socket removed");
            }
        }
    }

    /*
     * RegisteredSocket class to store the creation request of socket until the
     * LLCP link in not activated
     */
    private class RegisteredSocket {
        private final int mType;

        private final int mHandle;

        private final int mSap;

        private int mMiu;

        private int mRw;

        private String mServiceName;

        private int mlinearBufferLength;

        RegisteredSocket(int type, int handle, int sap, String sn, int miu, int rw,
                int linearBufferLength) {
            mType = type;
            mHandle = handle;
            mSap = sap;
            mServiceName = sn;
            mRw = rw;
            mMiu = miu;
            mlinearBufferLength = linearBufferLength;
        }

        RegisteredSocket(int type, int handle, int sap, int miu, int rw, int linearBufferLength) {
            mType = type;
            mHandle = handle;
            mSap = sap;
            mRw = rw;
            mMiu = miu;
            mlinearBufferLength = linearBufferLength;
        }

        RegisteredSocket(int type, int handle, int sap) {
            mType = type;
            mHandle = handle;
            mSap = sap;
        }
    }

    /** For use by code in this process */
    public LlcpSocket createLlcpSocket(int sap, int miu, int rw, int linearBufferLength) {
        try {
            int handle = mNfcAdapter.createLlcpSocket(sap, miu, rw, linearBufferLength);
            if (ErrorCodes.isError(handle)) {
                Log.e(TAG, "unable to create socket: " + ErrorCodes.asString(handle));
                return null;
            }
            return new LlcpSocket(mLlcpSocket, handle);
        } catch (RemoteException e) {
            // This will never happen since the code is calling into it's own process
            throw new IllegalStateException("unable to talk to myself", e);
        }
    }

    /** For use by code in this process */
    public LlcpServiceSocket createLlcpServiceSocket(int sap, String sn, int miu, int rw,
            int linearBufferLength) {
        try {
            int handle = mNfcAdapter.createLlcpServiceSocket(sap, sn, miu, rw, linearBufferLength);
            if (ErrorCodes.isError(handle)) {
                Log.e(TAG, "unable to create socket: " + ErrorCodes.asString(handle));
                return null;
            }
            return new LlcpServiceSocket(mLlcpServerSocketService, mLlcpSocket, handle);
        } catch (RemoteException e) {
            // This will never happen since the code is calling into it's own process
            throw new IllegalStateException("unable to talk to myself", e);
        }
    }

    private void activateLlcpLink() {
        /* check if sockets are registered */
        ListIterator<RegisteredSocket> it = mRegisteredSocketList.listIterator();

        if (DBG) Log.d(TAG, "Nb socket resgistered = " + mRegisteredSocketList.size());

        /* Mark the link state */
        mLlcpLinkState = NfcAdapter.LLCP_LINK_STATE_ACTIVATED;

        while (it.hasNext()) {
            RegisteredSocket registeredSocket = it.next();

            switch (registeredSocket.mType) {
            case LLCP_SERVICE_SOCKET_TYPE:
                if (DBG) Log.d(TAG, "Registered Llcp Service Socket");
                if (DBG) Log.d(TAG, "SAP: " + registeredSocket.mSap + ", SN: " + registeredSocket.mServiceName);
                NativeLlcpServiceSocket serviceSocket;

                serviceSocket = mManager.doCreateLlcpServiceSocket(
                        registeredSocket.mSap, registeredSocket.mServiceName,
                        registeredSocket.mMiu, registeredSocket.mRw,
                        registeredSocket.mlinearBufferLength);

                if (serviceSocket != null) {
                    if (DBG) Log.d(TAG, "service socket created");
                    /* Add the socket into the socket map */
                    synchronized(NfcService.this) {
                        mSocketMap.put(registeredSocket.mHandle, serviceSocket);
                    }
                } else {
                    Log.d(TAG, "FAILED to create service socket");
                    /* socket creation error - update the socket
                     * handle counter */
                    mGeneratedSocketHandle -= 1;
                }

                // NOTE: don't remove this socket from the registered sockets list.
                // If it's removed it won't be created the next time an LLCP
                // connection is activated and the server won't be found.
                break;

            case LLCP_SOCKET_TYPE:
                if (DBG) Log.d(TAG, "Registered Llcp Socket");
                NativeLlcpSocket clientSocket;
                clientSocket = mManager.doCreateLlcpSocket(registeredSocket.mSap,
                        registeredSocket.mMiu, registeredSocket.mRw,
                        registeredSocket.mlinearBufferLength);
                if (clientSocket != null) {
                    if (DBG) Log.d(TAG, "socket created");
                    /* Add the socket into the socket map */
                    synchronized(NfcService.this) {
                        mSocketMap.put(registeredSocket.mHandle, clientSocket);
                    }
                } else {
                    Log.d(TAG, "FAILED to create service socket");
                    /* socket creation error - update the socket
                     * handle counter */
                    mGeneratedSocketHandle -= 1;
                }
                // This socket has been created, remove it from the registered sockets list.
                it.remove();
                break;

            case LLCP_CONNECTIONLESS_SOCKET_TYPE:
                if (DBG) Log.d(TAG, "Registered Llcp Connectionless Socket");
                NativeLlcpConnectionlessSocket connectionlessSocket;
                connectionlessSocket = mManager.doCreateLlcpConnectionlessSocket(
                        registeredSocket.mSap);
                if (connectionlessSocket != null) {
                    if (DBG) Log.d(TAG, "connectionless socket created");
                    /* Add the socket into the socket map */
                    synchronized(NfcService.this) {
                        mSocketMap.put(registeredSocket.mHandle, connectionlessSocket);
                    }
                } else {
                    Log.d(TAG, "FAILED to create service socket");
                    /* socket creation error - update the socket
                     * handle counter */
                    mGeneratedSocketHandle -= 1;
                }
                // This socket has been created, remove it from the registered sockets list.
                it.remove();
                break;
            }
        }

        /* Broadcast Intent Link LLCP activated */
        Intent LlcpLinkIntent = new Intent();
        LlcpLinkIntent.setAction(NfcAdapter.ACTION_LLCP_LINK_STATE_CHANGED);

        LlcpLinkIntent.putExtra(NfcAdapter.EXTRA_LLCP_LINK_STATE_CHANGED,
                NfcAdapter.LLCP_LINK_STATE_ACTIVATED);

        if (DBG) Log.d(TAG, "Broadcasting LLCP activation");
        mContext.sendOrderedBroadcast(LlcpLinkIntent, NFC_PERM);
    }

    public void sendMockNdefTag(NdefMessage msg) {
        sendMessage(MSG_MOCK_NDEF, msg);
    }

    void sendMessage(int what, Object obj) {
        Message msg = mHandler.obtainMessage();
        msg.what = what;
        msg.obj = obj;
        mHandler.sendMessage(msg);
    }

    final class NfcServiceHandler extends Handler {

        public NdefMessage[] findAndReadNdef(NativeNfcTag nativeTag) {
            // Try to find NDEF on any of the technologies.
            int[] technologies = nativeTag.getTechList();
            int[] handles = nativeTag.getHandleList();
            int techIndex = 0;
            int lastHandleScanned = 0;
            boolean ndefFoundAndConnected = false;
            NdefMessage[] ndefMsgs = null;
            boolean foundFormattable = false;

            while ((!ndefFoundAndConnected) && (techIndex < technologies.length)) {
                if (handles[techIndex] != lastHandleScanned) {
                    // We haven't seen this handle yet, connect and checkndef
                    if (nativeTag.connect(technologies[techIndex])) {
                        // Check if this type is NDEF formatable
                        if (!foundFormattable && (nativeTag.isNdefFormatable())) {
                            foundFormattable = true;
                            nativeTag.addNdefFormatableTechnology(
                                    nativeTag.getConnectedHandle(),
                                    nativeTag.getConnectedTechnology());
                        } // else not formatable
                        int[] ndefinfo = new int[2];
                        if (nativeTag.checkNdef(ndefinfo)) {
                            ndefFoundAndConnected = true;
                            boolean generateEmptyNdef = false;

                            int supportedNdefLength = ndefinfo[0];
                            int cardState = ndefinfo[1];
                            byte[] buff = nativeTag.read();
                            if (buff != null) {
                                ndefMsgs = new NdefMessage[1];
                                try {
                                    ndefMsgs[0] = new NdefMessage(buff);
                                    nativeTag.addNdefTechnology(ndefMsgs[0],
                                            nativeTag.getConnectedHandle(),
                                            nativeTag.getConnectedLibNfcType(),
                                            nativeTag.getConnectedTechnology(),
                                            supportedNdefLength, cardState);
                                    nativeTag.reconnect();
                                } catch (FormatException e) {
                                   // Create an intent anyway, without NDEF messages
                                   generateEmptyNdef = true;
                                }
                            } else {
                                generateEmptyNdef = true;
                            }

                           if (generateEmptyNdef) {
                               ndefMsgs = new NdefMessage[] { };
                               nativeTag.addNdefTechnology(null,
                                       nativeTag.getConnectedHandle(),
                                       nativeTag.getConnectedLibNfcType(),
                                       nativeTag.getConnectedTechnology(),
                                       supportedNdefLength, cardState);
                               nativeTag.reconnect();
                           }
                        } // else, no NDEF on this tech, continue loop
                    } else {
                        // Connect failed, tag maybe lost. Try next handle
                        // anyway.
                    }
                }
                lastHandleScanned = handles[techIndex];
                techIndex++;
            }

            return ndefMsgs;
        }

        @Override
        public void handleMessage(Message msg) {
           switch (msg.what) {
           case MSG_MOCK_NDEF: {
               NdefMessage ndefMsg = (NdefMessage) msg.obj;
               Tag tag = Tag.createMockTag(new byte[] { 0x00 },
                       new int[] { },
                       new Bundle[] { });
               Log.d(TAG, "mock NDEF tag, starting corresponding activity");
               Log.d(TAG, tag.toString());
               dispatchTag(tag, new NdefMessage[] { ndefMsg });
               break;
           }

           case MSG_NDEF_TAG:
               if (DBG) Log.d(TAG, "Tag detected, notifying applications");
               NativeNfcTag nativeTag = (NativeNfcTag) msg.obj;
               NdefMessage[] ndefMsgs = findAndReadNdef(nativeTag);

               if (ndefMsgs != null) {
                   nativeTag.startPresenceChecking();
                   dispatchNativeTag(nativeTag, ndefMsgs);
               } else {
                   // No ndef found or connect failed, just try to reconnect and dispatch
                   if (nativeTag.reconnect()) {
                       nativeTag.startPresenceChecking();
                       dispatchNativeTag(nativeTag, null);
                   } else {
                       Log.w(TAG, "Failed to connect to tag");
                       nativeTag.disconnect();
                   }
               }
               break;

           case MSG_CARD_EMULATION:
               if (DBG) Log.d(TAG, "Card Emulation message");
               byte[] aid = (byte[]) msg.obj;
               /* Send broadcast ordered */
               Intent TransactionIntent = new Intent();
               TransactionIntent.setAction(NfcAdapter.ACTION_TRANSACTION_DETECTED);
               TransactionIntent.putExtra(NfcAdapter.EXTRA_AID, aid);
               if (DBG) Log.d(TAG, "Broadcasting Card Emulation event");
               mContext.sendOrderedBroadcast(TransactionIntent, NFC_PERM);
               break;

           case MSG_LLCP_LINK_ACTIVATION:
               NativeP2pDevice device = (NativeP2pDevice) msg.obj;

               Log.d(TAG, "LLCP Activation message");

               if (device.getMode() == NativeP2pDevice.MODE_P2P_TARGET) {
                   if (DBG) Log.d(TAG, "NativeP2pDevice.MODE_P2P_TARGET");
                   if (device.doConnect()) {
                       /* Check Llcp compliancy */
                       if (mManager.doCheckLlcp()) {
                           /* Activate Llcp Link */
                           if (mManager.doActivateLlcp()) {
                               if (DBG) Log.d(TAG, "Initiator Activate LLCP OK");
                               activateLlcpLink();
                           } else {
                               /* should not happen */
                               Log.w(TAG, "Initiator Activate LLCP NOK. Disconnect.");
                               device.doDisconnect();
                           }

                       } else {
                           if (DBG) Log.d(TAG, "Remote Target does not support LLCP. Disconnect.");
                           device.doDisconnect();
                       }
                   } else {
                       if (DBG) Log.d(TAG, "Cannot connect remote Target. Restart polling loop.");
                       device.doDisconnect();
                   }

               } else if (device.getMode() == NativeP2pDevice.MODE_P2P_INITIATOR) {
                   if (DBG) Log.d(TAG, "NativeP2pDevice.MODE_P2P_INITIATOR");
                   /* Check Llcp compliancy */
                   if (mManager.doCheckLlcp()) {
                       /* Activate Llcp Link */
                       if (mManager.doActivateLlcp()) {
                           if (DBG) Log.d(TAG, "Target Activate LLCP OK");
                           activateLlcpLink();
                      }
                   } else {
                       Log.w(TAG, "checkLlcp failed");
                   }
               }
               break;

           case MSG_LLCP_LINK_DEACTIVATED:
               device = (NativeP2pDevice) msg.obj;

               /* Mark the link state */
               mLlcpLinkState = NfcAdapter.LLCP_LINK_STATE_DEACTIVATED;

               Log.d(TAG, "LLCP Link Deactivated message. Restart polling loop.");
               if (device.getMode() == NativeP2pDevice.MODE_P2P_TARGET) {
                   if (DBG) Log.d(TAG, "disconnecting from target");
                   /* Restart polling loop */
                   device.doDisconnect();
               } else {
                   if (DBG) Log.d(TAG, "not disconnecting from initiator");
               }

               /* Broadcast Intent Link LLCP activated */
               Intent LlcpLinkIntent = new Intent();
               LlcpLinkIntent.setAction(NfcAdapter.ACTION_LLCP_LINK_STATE_CHANGED);
               LlcpLinkIntent.putExtra(NfcAdapter.EXTRA_LLCP_LINK_STATE_CHANGED,
                       NfcAdapter.LLCP_LINK_STATE_DEACTIVATED);
               if (DBG) Log.d(TAG, "Broadcasting LLCP deactivation");
               mContext.sendOrderedBroadcast(LlcpLinkIntent, NFC_PERM);
               break;

           case MSG_TARGET_DESELECTED:
               /* Broadcast Intent Target Deselected */
               if (DBG) Log.d(TAG, "Target Deselected");
               Intent TargetDeselectedIntent = new Intent();
               TargetDeselectedIntent.setAction(mManager.INTERNAL_TARGET_DESELECTED_ACTION);
               if (DBG) Log.d(TAG, "Broadcasting Intent");
               mContext.sendOrderedBroadcast(TargetDeselectedIntent, NFC_PERM);
               break;

           case MSG_SHOW_MY_TAG_ICON: {
               StatusBarManager sb = (StatusBarManager) getSystemService(
                       Context.STATUS_BAR_SERVICE);
               sb.setIcon("nfc", R.drawable.stat_sys_nfc, 0);
               break;
           }

           case MSG_HIDE_MY_TAG_ICON: {
               StatusBarManager sb = (StatusBarManager) getSystemService(
                       Context.STATUS_BAR_SERVICE);
               sb.removeIcon("nfc");
               break;
           }

           case MSG_SE_FIELD_ACTIVATED:{
               if (DBG) Log.d(TAG, "SE FIELD ACTIVATED");
               Intent eventFieldOnIntent = new Intent();
               eventFieldOnIntent.setAction(NfcAdapter.ACTION_RF_FIELD_ON_DETECTED);
               if (DBG) Log.d(TAG, "Broadcasting Intent");
               mContext.sendBroadcast(eventFieldOnIntent, NFC_PERM);
               break;
           }

           case MSG_SE_FIELD_DEACTIVATED:{
               if (DBG) Log.d(TAG, "SE FIELD DEACTIVATED");
               Intent eventFieldOffIntent = new Intent();
               eventFieldOffIntent.setAction(NfcAdapter.ACTION_RF_FIELD_OFF_DETECTED);
               if (DBG) Log.d(TAG, "Broadcasting Intent");
               mContext.sendBroadcast(eventFieldOffIntent, NFC_PERM);
               break;
           }

           default:
               Log.e(TAG, "Unknown message received");
               break;
           }
        }

        private Intent buildTagIntent(Tag tag, NdefMessage[] msgs, String action) {
            Intent intent = new Intent(action);
            intent.putExtra(NfcAdapter.EXTRA_TAG, tag);
            intent.putExtra(NfcAdapter.EXTRA_ID, tag.getId());
            intent.putExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, msgs);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return intent;
        }

        private void dispatchNativeTag(NativeNfcTag nativeTag, NdefMessage[] msgs) {
            Tag tag = new Tag(nativeTag.getUid(), nativeTag.getTechList(),
                    nativeTag.getTechExtras(), nativeTag.getHandle());
            if (dispatchTag(tag, msgs)) {
                registerTagObject(nativeTag);
            } else {
                nativeTag.disconnect();
            }
        }

        public byte[] concat(byte[]... arrays) {
            int length = 0;
            for (byte[] array : arrays) {
                length += array.length;
            }
            byte[] result = new byte[length];
            int pos = 0;
            for (byte[] array : arrays) {
                System.arraycopy(array, 0, result, pos, array.length);
                pos += array.length;
            }
            return result;
        }

        private Uri parseWellKnownUriRecord(NdefRecord record) {
            byte[] payload = record.getPayload();

            /*
             * payload[0] contains the URI Identifier Code, per the
             * NFC Forum "URI Record Type Definition" section 3.2.2.
             *
             * payload[1]...payload[payload.length - 1] contains the rest of
             * the URI.
             */
            String prefix = URI_PREFIX_MAP[(payload[0] & 0xff)];
            byte[] fullUri = concat(prefix.getBytes(Charsets.UTF_8),
                    Arrays.copyOfRange(payload, 1, payload.length));
            return Uri.parse(new String(fullUri, Charsets.UTF_8));
        }

        private boolean setTypeOrDataFromNdef(Intent intent, NdefRecord record) {
            short tnf = record.getTnf();
            byte[] type = record.getType();
            switch (tnf) {
                case NdefRecord.TNF_MIME_MEDIA: {
                    intent.setType(new String(type, Charsets.US_ASCII));
                    return true;
                }
                case NdefRecord.TNF_ABSOLUTE_URI: {
                    intent.setData(Uri.parse(new String(type, Charsets.UTF_8)));
                    return true;
                }
                case NdefRecord.TNF_WELL_KNOWN: {
                    if (Arrays.equals(type, NdefRecord.RTD_TEXT)) {
                        intent.setType("text/plain");
                        return true;
                    } else if (Arrays.equals(type, NdefRecord.RTD_SMART_POSTER)) {
                        // Parse the smart poster looking for the URI
                        try {
                            NdefMessage msg = new NdefMessage(record.getPayload());
                            for (NdefRecord subRecord : msg.getRecords()) {
                                if (subRecord.getTnf() == NdefRecord.TNF_WELL_KNOWN
                                        && Arrays.equals(subRecord.getType(), NdefRecord.RTD_URI)) {
                                    intent.setData(parseWellKnownUriRecord(subRecord));
                                    return true;
                                }
                            }
                        } catch (FormatException e) {
                            return false;
                        }
                    } else if (Arrays.equals(type, NdefRecord.RTD_URI)) {
                        intent.setData(parseWellKnownUriRecord(record));
                        return true;
                    }
                    return false;
                }
            }
            return false;
        }

        private Uri buildTechListUri(Tag tag) {
            int[] techList = tag.getTechnologyList();
            Arrays.sort(techList);
            Uri.Builder builder = new Uri.Builder();
            builder.scheme("vnd.android.nfc").authority("tag");
            for (int tech : techList) {
                builder.appendPath(Integer.toString(tech));
            }
            builder.appendPath("");
            return builder.build();
        }

        /** Returns false if no activities were found to dispatch to */
        private boolean dispatchTag(Tag tag, NdefMessage[] msgs) {
            if (DBG) {
                Log.d(TAG, "Dispatching tag");
                Log.d(TAG, tag.toString());
            }

            IntentFilter[] overrideFilters;
            PendingIntent overrideIntent;
            synchronized (mNfcAdapter) {
                overrideFilters = mDispatchOverrideFilters;
                overrideIntent = mDispatchOverrideIntent;
            }

            // First look for dispatch overrides
            if (overrideFilters != null && overrideIntent != null) {
                if (DBG) Log.d(TAG, "Attempting to dispatch tag with override");
                try { 
                    if (dispatchTagInternal(tag, msgs, overrideIntent, overrideFilters)) {
                        if (DBG) Log.d(TAG, "Dispatched to override");
                        return true;
                    }
                    Log.w(TAG, "Dispatch override registered, but no filters matched");
                } catch (CanceledException e) {
                    Log.w(TAG, "Dispatch overrides pending intent was canceled");
                    synchronized (mNfcAdapter) {
                        mDispatchOverrideFilters = null;
                        mDispatchOverrideIntent = null;
                    }
                }
            }

            // Try a standard dispatch
            try {
                return dispatchTagInternal(tag, msgs, null, null);
            } catch (CanceledException e) {
                Log.e(TAG, "CanceledException unexpected here", e);
                return false;
            }
        }

        // Dispatch to either an override pending intent or a standard startActivity()
        private boolean dispatchTagInternal(Tag tag, NdefMessage[] msgs,
                PendingIntent overrideIntent, IntentFilter[] overrideFilters)
                throws CanceledException{
            Intent intent;
            if (msgs != null && msgs.length > 0) {
                NdefMessage msg = msgs[0];
                NdefRecord[] records = msg.getRecords();
                if (records.length > 0) {
                    // Found valid NDEF data, try to dispatch that first
                    NdefRecord record = records[0];

                    intent = buildTagIntent(tag, msgs, NfcAdapter.ACTION_NDEF_DISCOVERED);
                    setTypeOrDataFromNdef(intent, record);

                    if (startDispatchActivity(intent, overrideIntent, overrideFilters)) {
                        // If an activity is found then skip further dispatching
                        return true;
                    } else {
                        if (DBG) Log.d(TAG, "No activities for NDEF handling of " + intent);
                    }
                }
            }

            // Try the technology specific dispatch
            intent = buildTagIntent(tag, msgs, NfcAdapter.ACTION_TECHNOLOGY_DISCOVERED);
            intent.setData(buildTechListUri(tag));
            if (startDispatchActivity(intent, overrideIntent, overrideFilters)) {
                return true;
            } else {
                if (DBG) Log.w(TAG, "No activities for technology handling of " + intent);
            }

            // Try the generic intent
            intent = buildTagIntent(tag, msgs, NfcAdapter.ACTION_TAG_DISCOVERED);
            if (startDispatchActivity(intent, overrideIntent, overrideFilters)) {
                return true;
            } else {
                Log.e(TAG, "No tag fallback activity found for " + intent);
                return false;
            }
        }

        private boolean startDispatchActivity(Intent intent, PendingIntent overrideIntent,
                IntentFilter[] overrideFilters) throws CanceledException {
            if (overrideIntent != null) {
                for (IntentFilter filter : overrideFilters) {
                    if (filter.match(mContext.getContentResolver(), intent, false, TAG) >= 0) {
                        Log.i(TAG, "Dispatching to override intent " + overrideIntent);
                        overrideIntent.send(mContext, Activity.RESULT_OK, intent);
                        return true;
                    }
                }
                return false;
            } else {
                try {
                    mContext.startActivity(intent);
                    return true;
                } catch (ActivityNotFoundException e) {
                    return false;
                }
            }
        }
    }

    private NfcServiceHandler mHandler = new NfcServiceHandler();
    
    private class EnableDisableDiscoveryTask extends AsyncTask<Boolean, Void, Void> {
        @Override
        protected Void doInBackground(Boolean... enable) {
            if (enable != null && enable.length > 0 && enable[0]) {
                synchronized (NfcService.this) {
                    mScreenOn = true;
                    maybeEnableDiscovery();
                }
            } else {
                mWakeLock.acquire();
                synchronized (NfcService.this) {
                    mScreenOn = false;
                    maybeDisableDiscovery();
                }
                mWakeLock.release();
            }
            return null;
        }
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(
                    NativeNfcManager.INTERNAL_TARGET_DESELECTED_ACTION)) {
                if (DBG) Log.d(TAG, "INERNAL_TARGET_DESELECTED_ACTION");

                /* Restart polling loop for notification */
                maybeEnableDiscovery();

            } else if (intent.getAction().equals(Intent.ACTION_SCREEN_ON)) {
                // Perform discovery enable in thread to protect against ANR when the
                // NFC stack wedges. This is *not* the correct way to fix this issue -
                // configuration of the local NFC adapter should be very quick and should
                // be safe on the main thread, and the NFC stack should not wedge.
                new EnableDisableDiscoveryTask().execute(new Boolean(true));
            } else if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
                // Perform discovery disable in thread to protect against ANR when the
                // NFC stack wedges. This is *not* the correct way to fix this issue -
                // configuration of the local NFC adapter should be very quick and should
                // be safe on the main thread, and the NFC stack should not wedge.
                new EnableDisableDiscoveryTask().execute(new Boolean(false));
            }
        }
    };
}
