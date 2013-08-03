/*
 * Kontalk Android client
 * Copyright (C) 2011 Kontalk Devteam <devteam@kontalk.org>

 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.kontalk.client;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

import org.kontalk.client.Protocol.RegistrationResponse;
import org.kontalk.client.Protocol.RegistrationResponse.RegistrationStatus;
import org.kontalk.client.Protocol.ServerInfoResponse;
import org.kontalk.client.Protocol.ValidationResponse;
import org.kontalk.client.Protocol.ValidationResponse.ValidationStatus;

import android.content.Context;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;


/**
 * A basic worker thread for doing number validation procedures.
 * It handles all the steps defined in phone number validation, from the
 * validation request to the received SMS and finally the authentication token
 * request.
 * @author Daniele Ricci
 * @version 1.0
 */
public class NumberValidator implements Runnable {
    private static final String TAG = NumberValidator.class.getSimpleName();

    /** Initialization */
    public static final int STEP_INIT = 0;
    /** Serverinfo check */
    public static final int STEP_CHECK_INFO = 1;
    /** Validation step (sending phone number and waiting for SMS) */
    public static final int STEP_VALIDATION = 2;
    /** Requesting authentication token */
    public static final int STEP_AUTH_TOKEN = 3;

    private final EndpointServer mServer;
    private final String mPhone;
    private final ClientConnection mClient;
    private NumberValidatorListener mListener;
    private volatile int mStep;
    private CharSequence mValidationCode;
    private boolean mAlreadyChecked;

    private Thread mThread;

    public NumberValidator(EndpointServer server, String phone) {
        mServer = server;
        mPhone = phone;
        mClient = new ClientConnection(mServer);
    }

    public synchronized void start() {
        if (mThread != null) throw new IllegalArgumentException("already started");
        mThread = new Thread(this);
        mThread.start();
    }

    @Override
    public void run() {
        try {
            // begin!
            if (mStep == STEP_INIT) {
                // check that server is authorized to generate auth tokens
                mStep = STEP_CHECK_INFO;
                boolean supportsToken = checkServer();

                // server doesn't support authentication token
                if (!supportsToken) {
                    if (mListener != null) {
                        mStep = STEP_INIT;
                        mListener.onServerCheckFailed(this);
                        return;
                    }
                }

                mAlreadyChecked = true;

                // request number validation via sms
                mStep = STEP_VALIDATION;
                mClient.reconnect();
                RegistrationResponse res = mClient.registerWait(mPhone);
                if (mListener != null) {
                    if (res.getStatus() == RegistrationStatus.STATUS_CONTINUE) {
                        String smsFrom = res.getSmsFrom();
                        Log.d(TAG, "using sms sender id: " + smsFrom);

                        mListener.onValidationRequested(this);
                    }
                    else {
                        // validation failed :(
                        mListener.onValidationFailed(this, res.getStatus());
                        mStep = STEP_INIT;
                        return;
                    }
                }

                // validation succeded! Waiting for the sms...
            }

            // sms received, request authentication token
            else if (mStep == STEP_AUTH_TOKEN) {
                if (!mAlreadyChecked) {
                    // server doesn't support authentication token
                    if (!checkServer()) {
                        if (mListener != null) {
                            mStep = STEP_INIT;
                            mListener.onServerCheckFailed(this);
                            return;
                        }
                    }
                }

                Log.d(TAG, "requesting authentication token");

                mClient.reconnect();
                ValidationResponse res = mClient.validateWait(mValidationCode.toString());
                if (mListener != null) {
                    if (res.getStatus() == ValidationStatus.STATUS_SUCCESS) {
                        if (res.hasToken()) {
                            String token = res.getToken();
                            if (!TextUtils.isEmpty(token))
                                mListener.onAuthTokenReceived(this, token);
                        }
                    }
                    else {
                        // authentication failed :(
                        mListener.onAuthTokenFailed(this, res.getStatus());
                        mStep = STEP_INIT;
                        return;
                    }
                }
            }
        }
        catch (Throwable e) {
            if (mListener != null)
                mListener.onError(this, e);

            mStep = STEP_INIT;
        }
        finally {
            mClient.close();
        }
    }

    /**
     * Shuts down this thread gracefully.
     */
    public synchronized void shutdown() {
        Log.w(TAG, "shutting down");
        try {
            if (mThread != null) {
                mClient.close();
                mThread.interrupt();
                mThread.join();
                mThread = null;
            }
        }
        catch (Exception e) {
            // ignored
        }
        Log.w(TAG, "exiting");
    }

    /** Forcibly inputs the validation code. */
    public void manualInput(CharSequence code) {
        mValidationCode = code;
        mStep = STEP_AUTH_TOKEN;
        // next start call will trigger the next condition
        mThread = null;
    }

    public int getStep() {
        return mStep;
    }

    private boolean checkServer() throws IOException {
        mClient.reconnect();
        ServerInfoResponse info = mClient.serverinfoWait();
        if (info != null) {
            List<String> list = info.getSupportsList();
            for (String support : list) {
                if ("auth_token".equals(support))
                    return true;
            }
            return false;
        }
        else {
            // error - notify listener
            throw new IOException("unable to request server information");
        }
    }

    public synchronized void setListener(NumberValidatorListener listener) {
        mListener = listener;
    }

    public abstract interface NumberValidatorListener {
        /** Called if an exception get thrown. */
        public void onError(NumberValidator v, Throwable e);

        /** Called if the server doesn't support registration/auth tokens. */
        public void onServerCheckFailed(NumberValidator v);

        /** Called on confirmation that the validation SMS is being sent. */
        public void onValidationRequested(NumberValidator v);

        /** Called if phone number validation failed. */
        public void onValidationFailed(NumberValidator v, RegistrationStatus reason);

        /** Called on receiving of authentication token. */
        public void onAuthTokenReceived(NumberValidator v, CharSequence token);

        /** Called if validation code has not been verified. */
        public void onAuthTokenFailed(NumberValidator v, ValidationStatus reason);
    }

    /**
     * Converts pretty much any phone number into E.164 format.
     * @param myNumber used to take the country code if not found in the number
     * @param lastResortCc manual country code last resort
     * @throws IllegalArgumentException if no country code is available.
     */
    public static String fixNumber(Context context, String number, String myNumber, int lastResortCc)
            throws NumberParseException {

        final TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        String myRegionCode = tm.getSimCountryIso();
        if (myRegionCode != null)
            myRegionCode = myRegionCode.toUpperCase(Locale.US);

        PhoneNumberUtil util = PhoneNumberUtil.getInstance();
        try {
            if (myNumber != null) {
                PhoneNumber myNum = util.parse(myNumber, myRegionCode);
                // use region code found in my number
                myRegionCode = util.getRegionCodeForNumber(myNum);
            }
        }
        catch (NumberParseException e) {
            // ehm :)
        }

        PhoneNumber parsedNum;
        try {
            parsedNum = util.parse(number, myRegionCode);
        }
        catch (NumberParseException e) {
            // parse failed with default region code, try last resort
            if (lastResortCc > 0) {
                myRegionCode = util.getRegionCodeForCountryCode(lastResortCc);
                parsedNum = util.parse(number, myRegionCode);
            }
            else
                throw e;
        }

        // a NumberParseException would have been thrown at this point
        return util.format(parsedNum, PhoneNumberFormat.E164);
    }

    /** Returns the (parsed) number stored in this device SIM card. */
    public static PhoneNumber getMyNumber(Context context) {
        try {
            final TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            final String regionCode = tm.getSimCountryIso().toUpperCase(Locale.US);
            return PhoneNumberUtil.getInstance().parse(tm.getLine1Number(), regionCode);
        }
        catch (Exception e) {
            return null;
        }
    }

    /** Returns the localized region name for the given region code. */
    public static String getRegionDisplayName(String regionCode, Locale language) {
        return (regionCode == null || regionCode.equals("ZZ") ||
                regionCode.equals(PhoneNumberUtil.REGION_CODE_FOR_NON_GEO_ENTITY))
            ? "" : new Locale("", regionCode).getDisplayCountry(language);
    }

}