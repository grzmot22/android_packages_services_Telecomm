/*
 * Copyright 2014, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.telecomm;

import android.content.ComponentName;
import android.os.IBinder;
import android.os.RemoteException;
import android.telecomm.CallInfo;
import android.telecomm.CallServiceInfo;
import android.telecomm.ICallService;
import android.telecomm.ICallServiceAdapter;
import android.util.Log;

/**
 * Wrapper for {@link ICallService}s, handles binding to {@link ICallService} and keeps track of
 * when the object can safely be unbound. Other classes should not use {@link ICallService} directly
 * and instead should use this class to invoke methods of {@link ICallService}.
 * TODO(santoscordon): Keep track of when the service can be safely unbound.
 * TODO(santoscordon): Look into combining with android.telecomm.CallService.
 */
public class CallServiceWrapper extends ServiceBinder<ICallService> {

    /**
     * The service action used to bind to ICallService implementations.
     * TODO(santoscordon): Move this to TelecommConstants.
     */
    static final String CALL_SERVICE_ACTION = ICallService.class.getName();

    private static final String TAG = CallServiceWrapper.class.getSimpleName();

    /** The descriptor of this call service as supplied by the call-service provider. */
    private final CallServiceInfo mCallServiceInfo;

    /**
     * The adapter used by the underlying call-service implementation to communicate with Telecomm.
     */
    private final CallServiceAdapter mAdapter;

    /** The actual service implementation. */
    private ICallService mServiceInterface;

    /**
     * Creates a call-service provider for the specified component.
     *
     * @param info The call-service descriptor from {@link ICallServiceProvider#lookupCallServices}.
     * @param adapter The call-service adapter.
     */
    public CallServiceWrapper(CallServiceInfo info, CallServiceAdapter adapter) {
        super(CALL_SERVICE_ACTION, info.getServiceComponent());
        mCallServiceInfo = info;
        mAdapter = adapter;
    }

    public CallServiceInfo getInfo() {
        return mCallServiceInfo;
    }

    /** See {@link ICallService#setCallServiceAdapter}. */
    public void setCallServiceAdapter(ICallServiceAdapter callServiceAdapter) {
        try {
            if (mServiceInterface == null) {
                Log.wtf(TAG, "setCallServiceAdapter() invoked while the service is unbound.");
            } else {
                mServiceInterface.setCallServiceAdapter(callServiceAdapter);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to setCallServiceAdapter.", e);
        }
    }

    /** See {@link ICallService#isCompatibleWith}. */
    public void isCompatibleWith(CallInfo callInfo) {
        try {
            if (mServiceInterface == null) {
                Log.wtf(TAG, "isCompatibleWith() invoked while the service is unbound.");
            } else {
                mServiceInterface.isCompatibleWith(callInfo);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed checking isCompatibleWith.", e);
        }
    }

    /** See {@link ICallService#call}. */
    public void call(CallInfo callInfo) {
        try {
            if (mServiceInterface == null) {
                Log.wtf(TAG, "call() invoked while the service is unbound.");
            } else {
                mServiceInterface.call(callInfo);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to place call " + callInfo.getId() + ".", e);
        }
    }

    /** See {@link ICallService#disconnect}. */
    public void disconnect(String callId) {
        try {
            if (mServiceInterface == null) {
                Log.wtf(TAG, "disconnect() invoked while the service is unbound.");
            } else {
                mServiceInterface.disconnect(callId);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to disconnect call " + callId + ".", e);
        }
    }

    /** {@inheritDoc} */
    @Override protected void setServiceInterface(IBinder binder) {
        mServiceInterface = ICallService.Stub.asInterface(binder);
        setCallServiceAdapter(mAdapter);
    }
}