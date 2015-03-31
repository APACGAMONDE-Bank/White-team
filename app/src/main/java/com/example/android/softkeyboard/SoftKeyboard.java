/*
 * Copyright (C) 2008-2009 The Android Open Source Project
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

package com.example.android.softkeyboard;

import android.inputmethodservice.InputMethodService;
import android.inputmethodservice.Keyboard;
import android.inputmethodservice.KeyboardView;
import android.text.InputType;
import android.text.method.MetaKeyKeyListener;
import android.util.Log;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.GeometryFactory;
import com.vividsolutions.jts.geom.LineString;
import com.vividsolutions.jts.geom.LinearRing;
import com.vividsolutions.jts.geom.MultiPoint;
import com.vividsolutions.jts.geom.Polygon;
import com.vividsolutions.jts.geom.PrecisionModel;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Example of writing an input method for a soft keyboard.  This code is
 * focused on simplicity over completeness, so it should in no way be considered
 * to be a complete soft keyboard implementation.  Its purpose is to provide
 * a basic example for how you would get started writing an input method, to
 * be fleshed out as appropriate.
 */
public class SoftKeyboard extends InputMethodService 
        implements KeyboardView.OnKeyboardActionListener {
    static final boolean DEBUG = false;
    
    /**
     * This boolean indicates the optional example code for performing
     * processing of hard keys in addition to regular text generation
     * from on-screen interaction.  It would be used for input methods that
     * perform language translations (such as converting text entered on 
     * a QWERTY keyboard to Chinese), but may not be used for input methods
     * that are primarily intended to be used for on-screen text entry.
     */
    static final boolean PROCESS_HARD_KEYS = true;

    private InputMethodManager mInputMethodManager;

    private LatinKeyboardView mInputView;
    private CandidateView mCandidateView;
    private CompletionInfo[] mCompletions;
    
    private StringBuilder mComposing = new StringBuilder();
    private boolean mPredictionOn;
    private boolean mCompletionOn;
    private int mLastDisplayWidth;
    private boolean mCapsLock;
    private long mLastShiftTime;
    private long mMetaState;
    
    private LatinKeyboard mSymbolsKeyboard;
    private LatinKeyboard mSymbolsShiftedKeyboard;
    private LatinKeyboard mQwertyKeyboard;
    private LatinKeyboard mRussianKeyboard;
    
    private LatinKeyboard mCurKeyboard;
    
    private String mWordSeparators;
    private String cppJsonString;
    private JSONObject cppJson;

        /**
         * Main initialization of the input method component.  Be sure to call
         * to super class.
         */
    @Override public void onCreate() {
        super.onCreate();
        mInputMethodManager = (InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
        mWordSeparators = getResources().getString(R.string.word_separators);


    }
    
    /**
     * This is the point where you can do all of your UI initialization.  It
     * is called after creation and any configuration change.
     */
    @Override public void onInitializeInterface() {
        if (mQwertyKeyboard != null) {
            // Configuration changes can happen after the keyboard gets recreated,
            // so we need to be able to re-build the keyboards if the available
            // space has changed.
            int displayWidth = getMaxWidth();
            if (displayWidth == mLastDisplayWidth) return;
            mLastDisplayWidth = displayWidth;
        }
        mQwertyKeyboard = new LatinKeyboard(this, R.xml.qwerty);
        mRussianKeyboard = new LatinKeyboard(this, R.xml.russian);
        mSymbolsKeyboard = new LatinKeyboard(this, R.xml.symbols);
        mSymbolsShiftedKeyboard = new LatinKeyboard(this, R.xml.symbols_shift);
    }
    
    /**
     * Called by the framework when your view for creating input needs to
     * be generated.  This will be called the first time your input method
     * is displayed, and every time it needs to be re-created such as due to
     * a configuration change.
     */
    @Override public View onCreateInputView() {
        mInputView = (LatinKeyboardView) getLayoutInflater().inflate(
                R.layout.input, null);
        mInputView.setOnKeyboardActionListener(this);
        mInputView.setKeyboard(mQwertyKeyboard);




        return mInputView;
    }

    /**
     * Called by the framework when your view for showing candidates needs to
     * be generated, like {@link #onCreateInputView}.
     */
    @Override public View onCreateCandidatesView() {
        mCandidateView = new CandidateView(this);
        mCandidateView.setService(this);
        return mCandidateView;
    }

    /**
     * This is the main point where we do our initialization of the input method
     * to begin operating on an application.  At this point we have been
     * bound to the client, and are now receiving all of the detailed information
     * about the target of our edits.
     */
    @Override public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        
        // Reset our state.  We want to do this even if restarting, because
        // the underlying state of the text editor could have changed in any way.
        mComposing.setLength(0);
        updateCandidates();
        
        if (!restarting) {
            // Clear shift states.
            mMetaState = 0;
        }
        
        mPredictionOn = false;
        mCompletionOn = false;
        mCompletions = null;
        
        // We are now going to initialize our state based on the type of
        // text being edited.
        switch (attribute.inputType & InputType.TYPE_MASK_CLASS) {
            case InputType.TYPE_CLASS_NUMBER:
            case InputType.TYPE_CLASS_DATETIME:
                // Numbers and dates default to the symbols keyboard, with
                // no extra features.
                mCurKeyboard = mSymbolsKeyboard;
                break;
                
            case InputType.TYPE_CLASS_PHONE:
                // Phones will also default to the symbols keyboard, though
                // often you will want to have a dedicated phone keyboard.
                mCurKeyboard = mSymbolsKeyboard;
                break;
                
            case InputType.TYPE_CLASS_TEXT:
                // This is general text editing.  We will default to the
                // normal alphabetic keyboard, and assume that we should
                // be doing predictive text (showing candidates as the
                // user types).
                mCurKeyboard = mQwertyKeyboard;
                mPredictionOn = true;
                
                // We now look for a few special variations of text that will
                // modify our behavior.
                int variation = attribute.inputType & InputType.TYPE_MASK_VARIATION;
                if (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                        variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) {
                    // Do not display predictions / what the user is typing
                    // when they are entering a password.
                    mPredictionOn = false;
                }
                
                if (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                        || variation == InputType.TYPE_TEXT_VARIATION_URI
                        || variation == InputType.TYPE_TEXT_VARIATION_FILTER) {
                    // Our predictions are not useful for e-mail addresses
                    // or URIs.
                    mPredictionOn = false;
                }
                
                if ((attribute.inputType & InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE) != 0) {
                    // If this is an auto-complete text view, then our predictions
                    // will not be shown and instead we will allow the editor
                    // to supply their own.  We only show the editor's
                    // candidates when in fullscreen mode, otherwise relying
                    // own it displaying its own UI.
                    mPredictionOn = false;
                    mCompletionOn = isFullscreenMode();
                }
                
                // We also want to look at the current state of the editor
                // to decide whether our alphabetic keyboard should start out
                // shifted.
                updateShiftKeyState(attribute);
                break;
                
            default:
                // For all unknown input types, default to the alphabetic
                // keyboard with no special features.
                mCurKeyboard = mQwertyKeyboard;
                updateShiftKeyState(attribute);
        }
        
        // Update the label on the enter key, depending on what the application
        // says it will do.
        mCurKeyboard.setImeOptions(getResources(), attribute.imeOptions);
    }

    /**
     * This is called when the user is done editing a field.  We can use
     * this to reset our state.
     */
    @Override public void onFinishInput() {
        super.onFinishInput();
        
        // Clear current composing text and candidates.
        mComposing.setLength(0);
        updateCandidates();
        
        // We only hide the candidates window when finishing input on
        // a particular editor, to avoid popping the underlying application
        // up and down if the user is entering text into the bottom of
        // its window.
        setCandidatesViewShown(false);
        
        mCurKeyboard = mQwertyKeyboard;
        if (mInputView != null) {
            mInputView.closing();
        }
    }
    
    @Override public void onStartInputView(EditorInfo attribute, boolean restarting) {
        super.onStartInputView(attribute, restarting);
        // Apply the selected keyboard to the input view.
        mInputView.setKeyboard(mCurKeyboard);
        mInputView.closing();
        final InputMethodSubtype subtype = mInputMethodManager.getCurrentInputMethodSubtype();
        mInputView.setSubtypeOnSpaceKey(subtype);


         cppJsonString = "{\"KeyWord\": [\n" +
                 "      { \"-name\": \"#define\" },\n" +
                 "      { \"-name\": \"#elif\" },\n" +
                 "      { \"-name\": \"#else\" },\n" +
                 "      { \"-name\": \"#endif\" },\n" +
                 "      { \"-name\": \"#error\" },\n" +
                 "      { \"-name\": \"#if\" },\n" +
                 "      { \"-name\": \"#ifdef\" },\n" +
                 "      { \"-name\": \"#ifndef\" },\n" +
                 "      { \"-name\": \"#include\" },\n" +
                 "      { \"-name\": \"#line\" },\n" +
                 "      { \"-name\": \"#pragma\" },\n" +
                 "      { \"-name\": \"#undef\" },\n" +
                 "      {\n" +
                 "        \"-name\": \"abort\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"void\",\n" +
                 "          \"Param\": { \"-name\": \"void\" }\n" +
                 "        }\n" +
                 "      },\n" +
                 "      {\n" +
                 "        \"-name\": \"abs\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"int\",\n" +
                 "          \"Param\": { \"-name\": \"int i\" }\n" +
                 "        }\n" +
                 "      },\n" +
                 "      { \"-name\": \"absread\" },\n" +
                 "      { \"-name\": \"abswrite\" },\n" +
                 "      {\n" +
                 "        \"-name\": \"access\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"int\",\n" +
                 "          \"Param\": [\n" +
                 "            { \"-name\": \"const char *path\" },\n" +
                 "            { \"-name\": \"int amode\" }\n" +
                 "          ]\n" +
                 "        }\n" +
                 "      },\n" +
                 "      { \"-name\": \"accumulate\" },\n" +
                 "      {\n" +
                 "        \"-name\": \"acos\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"double\",\n" +
                 "          \"Param\": { \"-name\": \"double x\" }\n" +
                 "        }\n" +
                 "      },\n" +
                 "      { \"-name\": \"acosl\" },\n" +
                 "      { \"-name\": \"address\" },\n" +
                 "      { \"-name\": \"adjacent_difference\" },\n" +
                 "      { \"-name\": \"adjacent_find\" },\n" +
                 "      { \"-name\": \"advance\" },\n" +
                 "      { \"-name\": \"allocate\" },\n" +
                 "      { \"-name\": \"allocator\" },\n" +
                 "      { \"-name\": \"allocmem\" },\n" +
                 "      { \"-name\": \"always_noconv\" },\n" +
                 "      { \"-name\": \"any\" },\n" +
                 "      { \"-name\": \"append\" },\n" +
                 "      { \"-name\": \"arc\" },\n" +
                 "      { \"-name\": \"arg\" },\n" +
                 "      {\n" +
                 "        \"-name\": \"asctime\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"char*\",\n" +
                 "          \"Param\": { \"-name\": \"const struct tm *timeptr\" }\n" +
                 "        }\n" +
                 "      },\n" +
                 "      {\n" +
                 "        \"-name\": \"asin\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"double\",\n" +
                 "          \"Param\": { \"-name\": \"double x\" }\n" +
                 "        }\n" +
                 "      },\n" +
                 "      { \"-name\": \"asinl\" },\n" +
                 "      { \"-name\": \"asm\" },\n" +
                 "      {\n" +
                 "        \"-name\": \"assert\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"void\",\n" +
                 "          \"Param\": { \"-name\": \"int expression\" }\n" +
                 "        }\n" +
                 "      },\n" +
                 "      { \"-name\": \"assign\" },\n" +
                 "      { \"-name\": \"at\" },\n" +
                 "      {\n" +
                 "        \"-name\": \"atan\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"double\",\n" +
                 "          \"Param\": { \"-name\": \"double x\" }\n" +
                 "        }\n" +
                 "      },\n" +
                 "      {\n" +
                 "        \"-name\": \"atan2\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"double\",\n" +
                 "          \"Param\": [\n" +
                 "            { \"-name\": \"double y\" },\n" +
                 "            { \"-name\": \"double x\" }\n" +
                 "          ]\n" +
                 "        }\n" +
                 "      },\n" +
                 "      { \"-name\": \"atan2l\" },\n" +
                 "      { \"-name\": \"atanl\" },\n" +
                 "      {\n" +
                 "        \"-name\": \"atexit\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"int\",\n" +
                 "          \"Param\": { \"-name\": \"void (*func)(void)\" }\n" +
                 "        }\n" +
                 "      },\n" +
                 "      {\n" +
                 "        \"-name\": \"atof\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"double\",\n" +
                 "          \"Param\": { \"-name\": \"const char *nptr\" }\n" +
                 "        }\n" +
                 "      },\n" +
                 "      {\n" +
                 "        \"-name\": \"atoi\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"int\",\n" +
                 "          \"Param\": { \"-name\": \"const char *nptr\" }\n" +
                 "        }\n" +
                 "      },\n" +
                 "      {\n" +
                 "        \"-name\": \"atol\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"long int\",\n" +
                 "          \"Param\": { \"-name\": \"const char *nptr\" }\n" +
                 "        }\n" +
                 "      },\n" +
                 "      { \"-name\": \"auto\" },\n" +
                 "      { \"-name\": \"auto_ptr\" },\n" +
                 "      { \"-name\": \"back\" },\n" +
                 "      { \"-name\": \"back_inserter\" },\n" +
                 "      { \"-name\": \"back_insert_iterator\" },\n" +
                 "      { \"-name\": \"bad\" },\n" +
                 "      { \"-name\": \"bar\" },\n" +
                 "      { \"-name\": \"bar3d\" },\n" +
                 "      { \"-name\": \"basic_string\" },\n" +
                 "      { \"-name\": \"bcd\" },\n" +
                 "      { \"-name\": \"bdos\" },\n" +
                 "      { \"-name\": \"bdosptr\" },\n" +
                 "      { \"-name\": \"begin\" },\n" +
                 "      { \"-name\": \"bidirectional_iterator\" },\n" +
                 "      { \"-name\": \"binary_function\" },\n" +
                 "      { \"-name\": \"binary_negate\" },\n" +
                 "      { \"-name\": \"binary_search\" },\n" +
                 "      { \"-name\": \"bind1st\" },\n" +
                 "      { \"-name\": \"bind2nd\" },\n" +
                 "      { \"-name\": \"binder1st\" },\n" +
                 "      { \"-name\": \"binder2nd\" },\n" +
                 "      { \"-name\": \"bioscom\" },\n" +
                 "      { \"-name\": \"biosdisk\" },\n" +
                 "      { \"-name\": \"biosequip\" },\n" +
                 "      { \"-name\": \"bioskey\" },\n" +
                 "      { \"-name\": \"biosmemory\" },\n" +
                 "      { \"-name\": \"biosprint\" },\n" +
                 "      { \"-name\": \"biostime\" },\n" +
                 "      { \"-name\": \"bitset\" },\n" +
                 "      { \"-name\": \"bool\" },\n" +
                 "      { \"-name\": \"boolalpha\" },\n" +
                 "      { \"-name\": \"break\" },\n" +
                 "      { \"-name\": \"brk\" },\n" +
                 "      {\n" +
                 "        \"-name\": \"bsearch\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"void *\",\n" +
                 "          \"Param\": [\n" +
                 "            { \"-name\": \"const void *key\" },\n" +
                 "            { \"-name\": \"const void *base\" },\n" +
                 "            { \"-name\": \"size_t nmemb\" },\n" +
                 "            { \"-name\": \"size_t size\" },\n" +
                 "            { \"-name\": \"int (*compar)(const void *, const void *)\" }\n" +
                 "          ]\n" +
                 "        }\n" +
                 "      },\n" +
                 "      { \"-name\": \"cabs\" },\n" +
                 "      { \"-name\": \"cabsl\" },\n" +
                 "      {\n" +
                 "        \"-name\": \"calloc\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"void *\",\n" +
                 "          \"Param\": [\n" +
                 "            { \"-name\": \"size_t nmemb\" },\n" +
                 "            { \"-name\": \"size_t size\" }\n" +
                 "          ]\n" +
                 "        }\n" +
                 "      },\n" +
                 "      { \"-name\": \"capacity\" },\n" +
                 "      { \"-name\": \"case\" },\n" +
                 "      { \"-name\": \"catch\" },\n" +
                 "      { \"-name\": \"category\" },\n" +
                 "      {\n" +
                 "        \"-name\": \"ceil\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"double\",\n" +
                 "          \"Param\": { \"-name\": \"double x\" }\n" +
                 "        }\n" +
                 "      },\n" +
                 "      { \"-name\": \"ceill\" },\n" +
                 "      { \"-name\": \"cerr\" },\n" +
                 "      { \"-name\": \"cgets\" },\n" +
                 "      { \"-name\": \"char\" },\n" +
                 "      { \"-name\": \"char_type\" },\n" +
                 "      {\n" +
                 "        \"-name\": \"chdir\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"int\",\n" +
                 "          \"Param\": { \"-name\": \"const char *path\" }\n" +
                 "        }\n" +
                 "      },\n" +
                 "      {\n" +
                 "        \"-name\": \"chmod\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"int\",\n" +
                 "          \"Param\": [\n" +
                 "            { \"-name\": \"const char *path\" },\n" +
                 "            { \"-name\": \"mode_t mode\" }\n" +
                 "          ]\n" +
                 "        }\n" +
                 "      },\n" +
                 "      { \"-name\": \"chsize\" },\n" +
                 "      { \"-name\": \"cin\" },\n" +
                 "      { \"-name\": \"circle\" },\n" +
                 "      { \"-name\": \"class\" },\n" +
                 "      { \"-name\": \"classic_table\" },\n" +
                 "      { \"-name\": \"clear\" },\n" +
                 "      { \"-name\": \"cleardevice\" },\n" +
                 "      {\n" +
                 "        \"-name\": \"clearerr\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"void\",\n" +
                 "          \"Param\": { \"-name\": \"FILE *stream\" }\n" +
                 "        }\n" +
                 "      },\n" +
                 "      { \"-name\": \"clearviewport\" },\n" +
                 "      {\n" +
                 "        \"-name\": \"clock\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"clock_t\",\n" +
                 "          \"Param\": { \"-name\": \"void\" }\n" +
                 "        }\n" +
                 "      },\n" +
                 "      { \"-name\": \"clog\" },\n" +
                 "      {\n" +
                 "        \"-name\": \"close\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"int\",\n" +
                 "          \"Param\": { \"-name\": \"int filedes\" }\n" +
                 "        }\n" +
                 "      },\n" +
                 "      {\n" +
                 "        \"-name\": \"closedir\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"int\",\n" +
                 "          \"Param\": { \"-name\": \"DIR *dirp\" }\n" +
                 "        }\n" +
                 "      },\n" +
                 "      { \"-name\": \"closegraph\" },\n" +
                 "      { \"-name\": \"clreol\" },\n" +
                 "      { \"-name\": \"clrscr\" },\n" +
                 "      { \"-name\": \"compare\" },\n" +
                 "      { \"-name\": \"complex\" },\n" +
                 "      { \"-name\": \"conj\" },\n" +
                 "      { \"-name\": \"const\" },\n" +
                 "      { \"-name\": \"construct\" },\n" +
                 "      { \"-name\": \"const_cast\" },\n" +
                 "      { \"-name\": \"const_pointer\" },\n" +
                 "      { \"-name\": \"const_reference\" },\n" +
                 "      { \"-name\": \"container\" },\n" +
                 "      { \"-name\": \"continue\" },\n" +
                 "      { \"-name\": \"copy\" },\n" +
                 "      { \"-name\": \"copyfmt\" },\n" +
                 "      { \"-name\": \"copy_backward\" },\n" +
                 "      { \"-name\": \"coreleft\" },\n" +
                 "      {\n" +
                 "        \"-name\": \"cos\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"double\",\n" +
                 "          \"Param\": { \"-name\": \"double x\" }\n" +
                 "        }\n" +
                 "      },\n" +
                 "      {\n" +
                 "        \"-name\": \"cosh\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"double\",\n" +
                 "          \"Param\": { \"-name\": \"double x\" }\n" +
                 "        }\n" +
                 "      },\n" +
                 "      { \"-name\": \"coshl\" },\n" +
                 "      { \"-name\": \"cosl\" },\n" +
                 "      { \"-name\": \"count\" },\n" +
                 "      { \"-name\": \"country\" },\n" +
                 "      { \"-name\": \"count_if\" },\n" +
                 "      { \"-name\": \"cout\" },\n" +
                 "      { \"-name\": \"cprintf\" },\n" +
                 "      { \"-name\": \"cputs\" },\n" +
                 "      {\n" +
                 "        \"-name\": \"creat\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"int\",\n" +
                 "          \"Param\": [\n" +
                 "            { \"-name\": \"const char *path\" },\n" +
                 "            { \"-name\": \"mode_t mode\" }\n" +
                 "          ]\n" +
                 "        }\n" +
                 "      },\n" +
                 "      { \"-name\": \"creatnew\" },\n" +
                 "      { \"-name\": \"creattemp\" },\n" +
                 "      { \"-name\": \"cscanf\" },\n" +
                 "      {\n" +
                 "        \"-name\": \"ctime\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"char *\",\n" +
                 "          \"Param\": { \"-name\": \"const time_t *timer\" }\n" +
                 "        }\n" +
                 "      },\n" +
                 "      { \"-name\": \"ctrlbrk\" },\n" +
                 "      { \"-name\": \"curr_symbol\" },\n" +
                 "      { \"-name\": \"c_str\" },\n" +
                 "      { \"-name\": \"data\" },\n" +
                 "      { \"-name\": \"date_order\" },\n" +
                 "      { \"-name\": \"deallocate\" },\n" +
                 "      { \"-name\": \"dec\" },\n" +
                 "      { \"-name\": \"decimal_point\" },\n" +
                 "      { \"-name\": \"default\" },\n" +
                 "      { \"-name\": \"delay\" },\n" +
                 "      { \"-name\": \"delete\" },\n" +
                 "      { \"-name\": \"delline\" },\n" +
                 "      { \"-name\": \"denorm_min\" },\n" +
                 "      { \"-name\": \"deque\" },\n" +
                 "      { \"-name\": \"destroy\" },\n" +
                 "      { \"-name\": \"detectgraph\" },\n" +
                 "      { \"-name\": \"difference_type\" },\n" +
                 "      {\n" +
                 "        \"-name\": \"difftime\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"double\",\n" +
                 "          \"Param\": [\n" +
                 "            { \"-name\": \"time_t time1\" },\n" +
                 "            { \"-name\": \"time_t time0\" }\n" +
                 "          ]\n" +
                 "        }\n" +
                 "      },\n" +
                 "      { \"-name\": \"digits\" },\n" +
                 "      { \"-name\": \"digits10\" },\n" +
                 "      { \"-name\": \"disable\" },\n" +
                 "      { \"-name\": \"distance\" },\n" +
                 "      {\n" +
                 "        \"-name\": \"div\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"div_t\",\n" +
                 "          \"Param\": [\n" +
                 "            { \"-name\": \"int numer\" },\n" +
                 "            { \"-name\": \"int denom\" }\n" +
                 "          ]\n" +
                 "        }\n" +
                 "      },\n" +
                 "      { \"-name\": \"divides\" },\n" +
                 "      { \"-name\": \"dllexport\" },\n" +
                 "      { \"-name\": \"dllexport2\" },\n" +
                 "      { \"-name\": \"dllimport\" },\n" +
                 "      { \"-name\": \"dllimport2\" },\n" +
                 "      { \"-name\": \"do\" },\n" +
                 "      { \"-name\": \"dosexterr\" },\n" +
                 "      { \"-name\": \"dostounix\" },\n" +
                 "      { \"-name\": \"double\" },\n" +
                 "      { \"-name\": \"do_always_noconv\" },\n" +
                 "      { \"-name\": \"do_close\" },\n" +
                 "      { \"-name\": \"do_compare\" },\n" +
                 "      { \"-name\": \"do_curr_symbol\" },\n" +
                 "      { \"-name\": \"do_decimal_point\" },\n" +
                 "      { \"-name\": \"do_encoding\" },\n" +
                 "      { \"-name\": \"do_get\" },\n" +
                 "      { \"-name\": \"do_grouping\" },\n" +
                 "      { \"-name\": \"do_hash\" },\n" +
                 "      { \"-name\": \"do_in\" },\n" +
                 "      { \"-name\": \"do_is\" },\n" +
                 "      { \"-name\": \"do_length\" },\n" +
                 "      { \"-name\": \"do_max_length\" },\n" +
                 "      { \"-name\": \"do_narrow\" },\n" +
                 "      { \"-name\": \"do_neg_format\" },\n" +
                 "      { \"-name\": \"do_open\" },\n" +
                 "      { \"-name\": \"do_out\" },\n" +
                 "      { \"-name\": \"do_pos_format\" },\n" +
                 "      { \"-name\": \"do_scan_is\" },\n" +
                 "      { \"-name\": \"do_scan_not\" },\n" +
                 "      { \"-name\": \"do_thousands_sep\" },\n" +
                 "      { \"-name\": \"do_tolower\" },\n" +
                 "      { \"-name\": \"do_toupper\" },\n" +
                 "      { \"-name\": \"do_transform\" },\n" +
                 "      { \"-name\": \"do_widen\" },\n" +
                 "      { \"-name\": \"drawpoly\" },\n" +
                 "      {\n" +
                 "        \"-name\": \"dup\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"int\",\n" +
                 "          \"Param\": { \"-name\": \"int filedes\" }\n" +
                 "        }\n" +
                 "      },\n" +
                 "      {\n" +
                 "        \"-name\": \"dup2\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"int\",\n" +
                 "          \"Param\": [\n" +
                 "            { \"-name\": \"int filedes\" },\n" +
                 "            { \"-name\": \"int filedes2\" }\n" +
                 "          ]\n" +
                 "        }\n" +
                 "      },\n" +
                 "      { \"-name\": \"dynamic_cast\" },\n" +
                 "      { \"-name\": \"eback\" },\n" +
                 "      { \"-name\": \"ecvt\" },\n" +
                 "      { \"-name\": \"egptr\" },\n" +
                 "      { \"-name\": \"ellipse\" },\n" +
                 "      { \"-name\": \"else\" },\n" +
                 "      { \"-name\": \"empty\" },\n" +
                 "      { \"-name\": \"enable\" },\n" +
                 "      { \"-name\": \"encoding\" },\n" +
                 "      { \"-name\": \"end\" },\n" +
                 "      { \"-name\": \"endl\" },\n" +
                 "      { \"-name\": \"ends\" },\n" +
                 "      { \"-name\": \"enum\" },\n" +
                 "      { \"-name\": \"eof\" },\n" +
                 "      { \"-name\": \"epptr\" },\n" +
                 "      { \"-name\": \"epsilon\" },\n" +
                 "      { \"-name\": \"eq\" },\n" +
                 "      { \"-name\": \"equal\" },\n" +
                 "      { \"-name\": \"equal_range\" },\n" +
                 "      { \"-name\": \"equal_to\" },\n" +
                 "      { \"-name\": \"eq_int_type\" },\n" +
                 "      { \"-name\": \"erase\" },\n" +
                 "      { \"-name\": \"event_callback\" },\n" +
                 "      { \"-name\": \"exceptions\" },\n" +
                 "      {\n" +
                 "        \"-name\": \"execl\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"int\",\n" +
                 "          \"Param\": [\n" +
                 "            { \"-name\": \"const char *path\" },\n" +
                 "            { \"-name\": \"const char *args\" },\n" +
                 "            { \"-name\": \"...\" }\n" +
                 "          ]\n" +
                 "        }\n" +
                 "      },\n" +
                 "      {\n" +
                 "        \"-name\": \"execle\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"int\",\n" +
                 "          \"Param\": [\n" +
                 "            { \"-name\": \"const char *path\" },\n" +
                 "            { \"-name\": \"const char *args\" },\n" +
                 "            { \"-name\": \"...\" }\n" +
                 "          ]\n" +
                 "        }\n" +
                 "      },\n" +
                 "      {\n" +
                 "        \"-name\": \"execlp\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"int\",\n" +
                 "          \"Param\": [\n" +
                 "            { \"-name\": \"const char *file\" },\n" +
                 "            { \"-name\": \"const char *args\" },\n" +
                 "            { \"-name\": \"...\" }\n" +
                 "          ]\n" +
                 "        }\n" +
                 "      },\n" +
                 "      { \"-name\": \"execlpe\" },\n" +
                 "      {\n" +
                 "        \"-name\": \"execv\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"int\",\n" +
                 "          \"Param\": [\n" +
                 "            { \"-name\": \"const char *path\" },\n" +
                 "            {\n" +
                 "              \"-name\": \"char *const argv[]\"\n" +
                 "            }\n" +
                 "          ]\n" +
                 "        }\n" +
                 "      },\n" +
                 "      {\n" +
                 "        \"-name\": \"execve\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"int\",\n" +
                 "          \"Param\": [\n" +
                 "            { \"-name\": \"const char *path\" },\n" +
                 "            {\n" +
                 "              \"-name\": \"char *const argv[]\"\n" +
                 "            },\n" +
                 "            { \"-name\": \"char *const *envp\" }\n" +
                 "          ]\n" +
                 "        }\n" +
                 "      },\n" +
                 "      {\n" +
                 "        \"-name\": \"execvp\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"int\",\n" +
                 "          \"Param\": [\n" +
                 "            { \"-name\": \"const char *file\" },\n" +
                 "            {\n" +
                 "              \"-name\": \"char *const argv[]\"\n" +
                 "            }\n" +
                 "          ]\n" +
                 "        }\n" +
                 "      },\n" +
                 "      { \"-name\": \"execvpe\" },\n" +
                 "      {\n" +
                 "        \"-name\": \"exit\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"void\",\n" +
                 "          \"Param\": { \"-name\": \"int status\" }\n" +
                 "        }\n" +
                 "      },\n" +
                 "      {\n" +
                 "        \"-name\": \"exp\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"double\",\n" +
                 "          \"Param\": { \"-name\": \"double x\" }\n" +
                 "        }\n" +
                 "      },\n" +
                 "      { \"-name\": \"expl\" },\n" +
                 "      { \"-name\": \"explicit\" },\n" +
                 "      { \"-name\": \"extern\" },\n" +
                 "      { \"-name\": \"extern_type\" },\n" +
                 "      {\n" +
                 "        \"-name\": \"fabs\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"double\",\n" +
                 "          \"Param\": { \"-name\": \"double x\" }\n" +
                 "        }\n" +
                 "      },\n" +
                 "      { \"-name\": \"fabsl\" },\n" +
                 "      { \"-name\": \"facet\" },\n" +
                 "      { \"-name\": \"fail\" },\n" +
                 "      { \"-name\": \"failed\" },\n" +
                 "      { \"-name\": \"failure\" },\n" +
                 "      { \"-name\": \"false\" },\n" +
                 "      { \"-name\": \"falsename\" },\n" +
                 "      { \"-name\": \"farcalloc\" },\n" +
                 "      { \"-name\": \"farcoreleft\" },\n" +
                 "      { \"-name\": \"farfree\" },\n" +
                 "      { \"-name\": \"farheapcheck\" },\n" +
                 "      { \"-name\": \"farheapcheckfree\" },\n" +
                 "      { \"-name\": \"farheapchecknode\" },\n" +
                 "      { \"-name\": \"farheapfillfree\" },\n" +
                 "      { \"-name\": \"farheapwalk\" },\n" +
                 "      { \"-name\": \"farmalloc\" },\n" +
                 "      { \"-name\": \"farrealloc\" },\n" +
                 "      {\n" +
                 "        \"-name\": \"fclose\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"int\",\n" +
                 "          \"Param\": { \"-name\": \"File *stream\" }\n" +
                 "        }\n" +
                 "      },\n" +
                 "      { \"-name\": \"fcloseall\" },\n" +
                 "      { \"-name\": \"fcvt\" },\n" +
                 "      {\n" +
                 "        \"-name\": \"fdopen\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"File *\",\n" +
                 "          \"Param\": [\n" +
                 "            { \"-name\": \"int filedes\" },\n" +
                 "            { \"-name\": \"const char *type\" }\n" +
                 "          ]\n" +
                 "        }\n" +
                 "      },\n" +
                 "      {\n" +
                 "        \"-name\": \"feof\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"int\",\n" +
                 "          \"Param\": { \"-name\": \"FILE *stream\" }\n" +
                 "        }\n" +
                 "      },\n" +
                 "      {\n" +
                 "        \"-name\": \"ferror\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"int\",\n" +
                 "          \"Param\": { \"-name\": \"FILE *stream\" }\n" +
                 "        }\n" +
                 "      },\n" +
                 "      {\n" +
                 "        \"-name\": \"fflush\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"int\",\n" +
                 "          \"Param\": { \"-name\": \"FILE *stream\" }\n" +
                 "        }\n" +
                 "      },\n" +
                 "      {\n" +
                 "        \"-name\": \"fgetc\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"int\",\n" +
                 "          \"Param\": { \"-name\": \"FILE *stream\" }\n" +
                 "        }\n" +
                 "      },\n" +
                 "      { \"-name\": \"fgetchar\" },\n" +
                 "      {\n" +
                 "        \"-name\": \"fgetpos\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"int\",\n" +
                 "          \"Param\": [\n" +
                 "            { \"-name\": \"FILE *stream\" },\n" +
                 "            { \"-name\": \"fpos_t *pos\" }\n" +
                 "          ]\n" +
                 "        }\n" +
                 "      },\n" +
                 "      {\n" +
                 "        \"-name\": \"fgets\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"char *\",\n" +
                 "          \"Param\": [\n" +
                 "            { \"-name\": \"char *s\" },\n" +
                 "            { \"-name\": \"int n\" },\n" +
                 "            { \"-name\": \"FILE *stream\" }\n" +
                 "          ]\n" +
                 "        }\n" +
                 "      },\n" +
                 "      { \"-name\": \"filebuf\" },\n" +
                 "      { \"-name\": \"filelength\" },\n" +
                 "      {\n" +
                 "        \"-name\": \"fileno\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"int\",\n" +
                 "          \"Param\": { \"-name\": \"FILE *stream\" }\n" +
                 "        }\n" +
                 "      },\n" +
                 "      { \"-name\": \"fill\" },\n" +
                 "      { \"-name\": \"fillellipse\" },\n" +
                 "      { \"-name\": \"fillpoly\" },\n" +
                 "      { \"-name\": \"fill_n\" },\n" +
                 "      { \"-name\": \"find\" },\n" +
                 "      { \"-name\": \"findfirst\" },\n" +
                 "      { \"-name\": \"findnext\" },\n" +
                 "      { \"-name\": \"find_end\" },\n" +
                 "      { \"-name\": \"find_first_not_of\" },\n" +
                 "      { \"-name\": \"find_first_of\" },\n" +
                 "      { \"-name\": \"find_if\" },\n" +
                 "      { \"-name\": \"find_last_not_of\" },\n" +
                 "      { \"-name\": \"find_last_of\" },\n" +
                 "      { \"-name\": \"fixed\" },\n" +
                 "      { \"-name\": \"flags\" },\n" +
                 "      { \"-name\": \"flip\" },\n" +
                 "      { \"-name\": \"float\" },\n" +
                 "      { \"-name\": \"floodfill\" },\n" +
                 "      {\n" +
                 "        \"-name\": \"floor\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"double\",\n" +
                 "          \"Param\": { \"-name\": \"double x\" }\n" +
                 "        }\n" +
                 "      },\n" +
                 "      { \"-name\": \"floorl\" },\n" +
                 "      { \"-name\": \"flush\" },\n" +
                 "      { \"-name\": \"flushall\" },\n" +
                 "      {\n" +
                 "        \"-name\": \"fmod\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"double\",\n" +
                 "          \"Param\": [\n" +
                 "            { \"-name\": \"double x\" },\n" +
                 "            { \"-name\": \"double y\" }\n" +
                 "          ]\n" +
                 "        }\n" +
                 "      },\n" +
                 "      { \"-name\": \"fmodl\" },\n" +
                 "      { \"-name\": \"fmtflags\" },\n" +
                 "      { \"-name\": \"fnmerge\" },\n" +
                 "      { \"-name\": \"fnsplit\" },\n" +
                 "      {\n" +
                 "        \"-name\": \"fopen\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"FILE *\",\n" +
                 "          \"Param\": [\n" +
                 "            { \"-name\": \"const char * file\" },\n" +
                 "            { \"-name\": \"const char * mode\" }\n" +
                 "          ]\n" +
                 "        }\n" +
                 "      },\n" +
                 "      { \"-name\": \"for\" },\n" +
                 "      { \"-name\": \"forward_iterator\" },\n" +
                 "      { \"-name\": \"for_each\" },\n" +
                 "      {\n" +
                 "        \"-name\": \"fprintf\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"int\",\n" +
                 "          \"Param\": [\n" +
                 "            { \"-name\": \"FILE *stream\" },\n" +
                 "            { \"-name\": \"const char *format\" },\n" +
                 "            { \"-name\": \"...\" }\n" +
                 "          ]\n" +
                 "        }\n" +
                 "      },\n" +
                 "      {\n" +
                 "        \"-name\": \"fputc\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"int\",\n" +
                 "          \"Param\": [\n" +
                 "            { \"-name\": \"int c\" },\n" +
                 "            { \"-name\": \"FILE *stream\" }\n" +
                 "          ]\n" +
                 "        }\n" +
                 "      },\n" +
                 "      { \"-name\": \"fputchar\" },\n" +
                 "      {\n" +
                 "        \"-name\": \"fputs\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"int\",\n" +
                 "          \"Param\": [\n" +
                 "            { \"-name\": \"const char *s\" },\n" +
                 "            { \"-name\": \"FILE *stream\" }\n" +
                 "          ]\n" +
                 "        }\n" +
                 "      },\n" +
                 "      { \"-name\": \"FP_OFF\" },\n" +
                 "      { \"-name\": \"FP_SEG\" },\n" +
                 "      { \"-name\": \"frac_digits\" },\n" +
                 "      {\n" +
                 "        \"-name\": \"fread\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"size_t\",\n" +
                 "          \"Param\": [\n" +
                 "            { \"-name\": \"void *ptr\" },\n" +
                 "            { \"-name\": \"size_t size\" },\n" +
                 "            { \"-name\": \"size_t nmemb\" },\n" +
                 "            { \"-name\": \"FILE *stream\" }\n" +
                 "          ]\n" +
                 "        }\n" +
                 "      },\n" +
                 "      {\n" +
                 "        \"-name\": \"free\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"void\",\n" +
                 "          \"Param\": { \"-name\": \"void *ptr\" }\n" +
                 "        }\n" +
                 "      },\n" +
                 "      { \"-name\": \"freemem\" },\n" +
                 "      { \"-name\": \"freeze\" },\n" +
                 "      {\n" +
                 "        \"-name\": \"freopen\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"FILE *\",\n" +
                 "          \"Param\": [\n" +
                 "            { \"-name\": \"const char *filename\" },\n" +
                 "            { \"-name\": \"const char *mode\" },\n" +
                 "            { \"-name\": \"FILE *stream\" }\n" +
                 "          ]\n" +
                 "        }\n" +
                 "      },\n" +
                 "      {\n" +
                 "        \"-name\": \"frexp\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"double\",\n" +
                 "          \"Param\": [\n" +
                 "            { \"-name\": \"double value\" },\n" +
                 "            { \"-name\": \"int *exp\" }\n" +
                 "          ]\n" +
                 "        }\n" +
                 "      },\n" +
                 "      { \"-name\": \"frexpl\" },\n" +
                 "      { \"-name\": \"friend\" },\n" +
                 "      { \"-name\": \"front\" },\n" +
                 "      { \"-name\": \"front_inserter\" },\n" +
                 "      { \"-name\": \"front_insert_iterator\" },\n" +
                 "      {\n" +
                 "        \"-name\": \"fscanf\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"int\",\n" +
                 "          \"Param\": [\n" +
                 "            { \"-name\": \"FILE *stream\" },\n" +
                 "            { \"-name\": \"const char *format\" },\n" +
                 "            { \"-name\": \"...\" }\n" +
                 "          ]\n" +
                 "        }\n" +
                 "      },\n" +
                 "      {\n" +
                 "        \"-name\": \"fseek\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"int\",\n" +
                 "          \"Param\": [\n" +
                 "            { \"-name\": \"FILE *stream\" },\n" +
                 "            { \"-name\": \"long int offset\" },\n" +
                 "            { \"-name\": \"int whence\" }\n" +
                 "          ]\n" +
                 "        }\n" +
                 "      },\n" +
                 "      {\n" +
                 "        \"-name\": \"fsetpos\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"int\",\n" +
                 "          \"Param\": [\n" +
                 "            { \"-name\": \"FILE *stream\" },\n" +
                 "            { \"-name\": \"const fpos_t * pos\" }\n" +
                 "          ]\n" +
                 "        }\n" +
                 "      },\n" +
                 "      {\n" +
                 "        \"-name\": \"fstat\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"int\",\n" +
                 "          \"Param\": [\n" +
                 "            { \"-name\": \"int filedes\" },\n" +
                 "            { \"-name\": \"struct stat *buf\" }\n" +
                 "          ]\n" +
                 "        }\n" +
                 "      },\n" +
                 "      { \"-name\": \"fstream\" },\n" +
                 "      {\n" +
                 "        \"-name\": \"ftell\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"long int\",\n" +
                 "          \"Param\": { \"-name\": \"FILE *stream\" }\n" +
                 "        }\n" +
                 "      },\n" +
                 "      { \"-name\": \"ftime\" },\n" +
                 "      {\n" +
                 "        \"-name\": \"fwrite\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"size_t\",\n" +
                 "          \"Param\": [\n" +
                 "            { \"-name\": \"const void *ptr\" },\n" +
                 "            { \"-name\": \"size_t size\" },\n" +
                 "            { \"-name\": \"size_t nmemb\" },\n" +
                 "            { \"-name\": \"FILE *stream\" }\n" +
                 "          ]\n" +
                 "        }\n" +
                 "      },\n" +
                 "      { \"-name\": \"gbump\" },\n" +
                 "      { \"-name\": \"gcount\" },\n" +
                 "      { \"-name\": \"gcvt\" },\n" +
                 "      { \"-name\": \"generate\" },\n" +
                 "      { \"-name\": \"generate_n\" },\n" +
                 "      { \"-name\": \"geninterrupt\" },\n" +
                 "      { \"-name\": \"get\" },\n" +
                 "      { \"-name\": \"getarccoords\" },\n" +
                 "      { \"-name\": \"getaspectratio\" },\n" +
                 "      { \"-name\": \"getbkcolor\" },\n" +
                 "      {\n" +
                 "        \"-name\": \"getc\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"int\",\n" +
                 "          \"Param\": { \"-name\": \"FILE *stream\" }\n" +
                 "        }\n" +
                 "      },\n" +
                 "      { \"-name\": \"getcbrk\" },\n" +
                 "      { \"-name\": \"getch\" },\n" +
                 "      {\n" +
                 "        \"-name\": \"getchar\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"int\",\n" +
                 "          \"Param\": { \"-name\": \"void\" }\n" +
                 "        }\n" +
                 "      },\n" +
                 "      { \"-name\": \"getche\" },\n" +
                 "      { \"-name\": \"getcolor\" },\n" +
                 "      { \"-name\": \"getcurdir\" },\n" +
                 "      {\n" +
                 "        \"-name\": \"getcwd\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"char *\",\n" +
                 "          \"Param\": [\n" +
                 "            { \"-name\": \"char *buf\" },\n" +
                 "            { \"-name\": \"size_t size\" }\n" +
                 "          ]\n" +
                 "        }\n" +
                 "      },\n" +
                 "      { \"-name\": \"getdate\" },\n" +
                 "      { \"-name\": \"getdefaultpalette\" },\n" +
                 "      { \"-name\": \"getdfree\" },\n" +
                 "      { \"-name\": \"getdisk\" },\n" +
                 "      { \"-name\": \"getdrivername\" },\n" +
                 "      { \"-name\": \"getdta\" },\n" +
                 "      {\n" +
                 "        \"-name\": \"getenv\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"char *\",\n" +
                 "          \"Param\": { \"-name\": \"const char *name\" }\n" +
                 "        }\n" +
                 "      },\n" +
                 "      { \"-name\": \"getfat\" },\n" +
                 "      { \"-name\": \"getfatd\" },\n" +
                 "      { \"-name\": \"getfillpattern\" },\n" +
                 "      { \"-name\": \"getfillsettings\" },\n" +
                 "      { \"-name\": \"getftime\" },\n" +
                 "      { \"-name\": \"getgraphmode\" },\n" +
                 "      { \"-name\": \"getimage\" },\n" +
                 "      { \"-name\": \"getline\" },\n" +
                 "      { \"-name\": \"getlinesettings\" },\n" +
                 "      { \"-name\": \"getloc\" },\n" +
                 "      { \"-name\": \"getmaxcolor\" },\n" +
                 "      { \"-name\": \"getmaxmode\" },\n" +
                 "      { \"-name\": \"getmaxx\" },\n" +
                 "      { \"-name\": \"getmaxy\" },\n" +
                 "      { \"-name\": \"getmodename\" },\n" +
                 "      { \"-name\": \"getmoderange\" },\n" +
                 "      { \"-name\": \"getpalette\" },\n" +
                 "      { \"-name\": \"getpalettesize\" },\n" +
                 "      { \"-name\": \"getpass\" },\n" +
                 "      {\n" +
                 "        \"-name\": \"getpid\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"pid_t\",\n" +
                 "          \"Param\": { \"-name\": \"void\" }\n" +
                 "        }\n" +
                 "      },\n" +
                 "      { \"-name\": \"getpixel\" },\n" +
                 "      { \"-name\": \"getpsp\" },\n" +
                 "      {\n" +
                 "        \"-name\": \"gets\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"char *\",\n" +
                 "          \"Param\": { \"-name\": \"char *s\" }\n" +
                 "        }\n" +
                 "      },\n" +
                 "      { \"-name\": \"gettext\" },\n" +
                 "      { \"-name\": \"gettextinfo\" },\n" +
                 "      { \"-name\": \"gettextsettings\" },\n" +
                 "      { \"-name\": \"gettime\" },\n" +
                 "      { \"-name\": \"getvect\" },\n" +
                 "      { \"-name\": \"getverify\" },\n" +
                 "      { \"-name\": \"getviewsettings\" },\n" +
                 "      { \"-name\": \"getw\" },\n" +
                 "      { \"-name\": \"getx\" },\n" +
                 "      { \"-name\": \"gety\" },\n" +
                 "      { \"-name\": \"get_allocator\" },\n" +
                 "      { \"-name\": \"get_date\" },\n" +
                 "      { \"-name\": \"get_monthname\" },\n" +
                 "      { \"-name\": \"get_temporary_buffer\" },\n" +
                 "      { \"-name\": \"get_time\" },\n" +
                 "      { \"-name\": \"get_weekday\" },\n" +
                 "      { \"-name\": \"get_year\" },\n" +
                 "      {\n" +
                 "        \"-name\": \"gmtime\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"struct tm *\",\n" +
                 "          \"Param\": { \"-name\": \"const time_t *timer\" }\n" +
                 "        }\n" +
                 "      },\n" +
                 "      { \"-name\": \"good\" },\n" +
                 "      { \"-name\": \"goto\" },\n" +
                 "      { \"-name\": \"gotoxy\" },\n" +
                 "      { \"-name\": \"gptr\" },\n" +
                 "      { \"-name\": \"graphdefaults\" },\n" +
                 "      { \"-name\": \"grapherrormsg\" },\n" +
                 "      { \"-name\": \"graphresult\" },\n" +
                 "      { \"-name\": \"greater\" },\n" +
                 "      { \"-name\": \"greater_equal\" },\n" +
                 "      { \"-name\": \"grouping\" },\n" +
                 "      { \"-name\": \"harderr\" },\n" +
                 "      { \"-name\": \"hardresume\" },\n" +
                 "      { \"-name\": \"hardretn\" },\n" +
                 "      { \"-name\": \"hash\" },\n" +
                 "      { \"-name\": \"has_denorm\" },\n" +
                 "      { \"-name\": \"has_infinity\" },\n" +
                 "      { \"-name\": \"has_quiet_NaN\" },\n" +
                 "      { \"-name\": \"has_signaling_NaN\" },\n" +
                 "      { \"-name\": \"heapcheck\" },\n" +
                 "      { \"-name\": \"heapcheckfree\" },\n" +
                 "      { \"-name\": \"heapchecknode\" },\n" +
                 "      { \"-name\": \"heapfillfree\" },\n" +
                 "      { \"-name\": \"heapwalk\" },\n" +
                 "      { \"-name\": \"hex\" },\n" +
                 "      { \"-name\": \"highvideo\" },\n" +
                 "      { \"-name\": \"hypot\" },\n" +
                 "      { \"-name\": \"hypotl\" },\n" +
                 "      { \"-name\": \"id\" },\n" +
                 "      { \"-name\": \"if\" },\n" +
                 "      { \"-name\": \"ifstream\" },\n" +
                 "      { \"-name\": \"ignore\" },\n" +
                 "      { \"-name\": \"imag\" },\n" +
                 "      { \"-name\": \"imagesize\" },\n" +
                 "      { \"-name\": \"imbue\" },\n" +
                 "      { \"-name\": \"in\" },\n" +
                 "      { \"-name\": \"includes\" },\n" +
                 "      { \"-name\": \"infinity\" },\n" +
                 "      { \"-name\": \"init\" },\n" +
                 "      { \"-name\": \"initgraph\" },\n" +
                 "      { \"-name\": \"inline\" },\n" +
                 "      { \"-name\": \"inner_product\" },\n" +
                 "      { \"-name\": \"inp\" },\n" +
                 "      { \"-name\": \"inplace_merge\" },\n" +
                 "      { \"-name\": \"inport\" },\n" +
                 "      { \"-name\": \"inportb\" },\n" +
                 "      { \"-name\": \"input_iterator\" },\n" +
                 "      { \"-name\": \"inpw\" },\n" +
                 "      { \"-name\": \"insert\" },\n" +
                 "      { \"-name\": \"inserter\" },\n" +
                 "      { \"-name\": \"insert_iterator\" },\n" +
                 "      { \"-name\": \"insline\" },\n" +
                 "      { \"-name\": \"installuserdriver\" },\n" +
                 "      { \"-name\": \"installuserfont\" },\n" +
                 "      { \"-name\": \"int\" },\n" +
                 "      { \"-name\": \"int86\" },\n" +
                 "      { \"-name\": \"int86x\" },\n" +
                 "      { \"-name\": \"intdos\" },\n" +
                 "      { \"-name\": \"intdosx\" },\n" +
                 "      { \"-name\": \"internal\" },\n" +
                 "      { \"-name\": \"intern_type\" },\n" +
                 "      { \"-name\": \"Intl\" },\n" +
                 "      { \"-name\": \"intr\" },\n" +
                 "      { \"-name\": \"int_type\" },\n" +
                 "      { \"-name\": \"ioctl\" },\n" +
                 "      { \"-name\": \"ios\" },\n" +
                 "      { \"-name\": \"iostate\" },\n" +
                 "      { \"-name\": \"ios_type\" },\n" +
                 "      { \"-name\": \"is\" },\n" +
                 "      {\n" +
                 "        \"-name\": \"isalnum\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"int\",\n" +
                 "          \"Param\": { \"-name\": \"int c\" }\n" +
                 "        }\n" +
                 "      },\n" +
                 "      {\n" +
                 "        \"-name\": \"isalpha\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"int\",\n" +
                 "          \"Param\": { \"-name\": \"int c\" }\n" +
                 "        }\n" +
                 "      },\n" +
                 "      { \"-name\": \"isascii\" },\n" +
                 "      {\n" +
                 "        \"-name\": \"isatty\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"int\",\n" +
                 "          \"Param\": { \"-name\": \"int filedes\" }\n" +
                 "        }\n" +
                 "      },\n" +
                 "      {\n" +
                 "        \"-name\": \"iscntrl\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"int\",\n" +
                 "          \"Param\": { \"-name\": \"int c\" }\n" +
                 "        }\n" +
                 "      },\n" +
                 "      {\n" +
                 "        \"-name\": \"isdigit\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"int\",\n" +
                 "          \"Param\": { \"-name\": \"int c\" }\n" +
                 "        }\n" +
                 "      },\n" +
                 "      {\n" +
                 "        \"-name\": \"isgraph\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"int\",\n" +
                 "          \"Param\": { \"-name\": \"int c\" }\n" +
                 "        }\n" +
                 "      },\n" +
                 "      {\n" +
                 "        \"-name\": \"islower\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"int\",\n" +
                 "          \"Param\": { \"-name\": \"int c\" }\n" +
                 "        }\n" +
                 "      },\n" +
                 "      {\n" +
                 "        \"-name\": \"isprint\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"int\",\n" +
                 "          \"Param\": { \"-name\": \"int c\" }\n" +
                 "        }\n" +
                 "      },\n" +
                 "      {\n" +
                 "        \"-name\": \"ispunct\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"int\",\n" +
                 "          \"Param\": { \"-name\": \"int c\" }\n" +
                 "        }\n" +
                 "      },\n" +
                 "      {\n" +
                 "        \"-name\": \"isspace\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"int\",\n" +
                 "          \"Param\": { \"-name\": \"int c\" }\n" +
                 "        }\n" +
                 "      },\n" +
                 "      { \"-name\": \"istream\" },\n" +
                 "      { \"-name\": \"istream_type\" },\n" +
                 "      { \"-name\": \"istringstream\" },\n" +
                 "      {\n" +
                 "        \"-name\": \"isupper\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"int\",\n" +
                 "          \"Param\": { \"-name\": \"int c\" }\n" +
                 "        }\n" +
                 "      },\n" +
                 "      {\n" +
                 "        \"-name\": \"isxdigit\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"int\",\n" +
                 "          \"Param\": { \"-name\": \"int c\" }\n" +
                 "        }\n" +
                 "      },\n" +
                 "      { \"-name\": \"is_bounded\" },\n" +
                 "      { \"-name\": \"is_exact\" },\n" +
                 "      { \"-name\": \"is_iec559\" },\n" +
                 "      { \"-name\": \"is_integer\" },\n" +
                 "      { \"-name\": \"is_modulo\" },\n" +
                 "      { \"-name\": \"is_open\" },\n" +
                 "      { \"-name\": \"is_signed\" },\n" +
                 "      { \"-name\": \"is_specialized\" },\n" +
                 "      { \"-name\": \"is_sync\" },\n" +
                 "      { \"-name\": \"iter_swap\" },\n" +
                 "      { \"-name\": \"iter_type\" },\n" +
                 "      { \"-name\": \"itoa\" },\n" +
                 "      { \"-name\": \"iword\" },\n" +
                 "      { \"-name\": \"kbhit\" },\n" +
                 "      { \"-name\": \"keep\" },\n" +
                 "      { \"-name\": \"key_comp\" },\n" +
                 "      {\n" +
                 "        \"-name\": \"labs\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"long int\",\n" +
                 "          \"Param\": { \"-name\": \"long int i\" }\n" +
                 "        }\n" +
                 "      },\n" +
                 "      {\n" +
                 "        \"-name\": \"ldexp\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"double\",\n" +
                 "          \"Param\": [\n" +
                 "            { \"-name\": \"double x\" },\n" +
                 "            { \"-name\": \"int exp\" }\n" +
                 "          ]\n" +
                 "        }\n" +
                 "      },\n" +
                 "      { \"-name\": \"ldexpl\" },\n" +
                 "      {\n" +
                 "        \"-name\": \"ldiv\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"ldiv_t\",\n" +
                 "          \"Param\": [\n" +
                 "            { \"-name\": \"long int numer\" },\n" +
                 "            { \"-name\": \"long int denom\" }\n" +
                 "          ]\n" +
                 "        }\n" +
                 "      },\n" +
                 "      { \"-name\": \"left\" },\n" +
                 "      { \"-name\": \"length\" },\n" +
                 "      { \"-name\": \"less\" },\n" +
                 "      { \"-name\": \"less_equal\" },\n" +
                 "      { \"-name\": \"lexicographical_compare\" },\n" +
                 "      { \"-name\": \"lfind\" },\n" +
                 "      { \"-name\": \"line\" },\n" +
                 "      { \"-name\": \"linerel\" },\n" +
                 "      { \"-name\": \"lineto\" },\n" +
                 "      { \"-name\": \"list\" },\n" +
                 "      {\n" +
                 "        \"-name\": \"localeconv\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"struct lconv *\",\n" +
                 "          \"Param\": { \"-name\": \"void\" }\n" +
                 "        }\n" +
                 "      },\n" +
                 "      {\n" +
                 "        \"-name\": \"localtime\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"struct tm *\",\n" +
                 "          \"Param\": { \"-name\": \"const time_t *timer\" }\n" +
                 "        }\n" +
                 "      },\n" +
                 "      { \"-name\": \"lock\" },\n" +
                 "      { \"-name\": \"locking\" },\n" +
                 "      {\n" +
                 "        \"-name\": \"log\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"double\",\n" +
                 "          \"Param\": { \"-name\": \"double x\" }\n" +
                 "        }\n" +
                 "      },\n" +
                 "      {\n" +
                 "        \"-name\": \"log10\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"double\",\n" +
                 "          \"Param\": { \"-name\": \"double x\" }\n" +
                 "        }\n" +
                 "      },\n" +
                 "      { \"-name\": \"log10l\" },\n" +
                 "      { \"-name\": \"logical_and\" },\n" +
                 "      { \"-name\": \"logical_not\" },\n" +
                 "      { \"-name\": \"logical_or\" },\n" +
                 "      { \"-name\": \"logl\" },\n" +
                 "      { \"-name\": \"long\" },\n" +
                 "      {\n" +
                 "        \"-name\": \"longjmp\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"void\",\n" +
                 "          \"Param\": [\n" +
                 "            { \"-name\": \"jmp_buf env\" },\n" +
                 "            { \"-name\": \"int val\" }\n" +
                 "          ]\n" +
                 "        }\n" +
                 "      },\n" +
                 "      { \"-name\": \"lower_bound\" },\n" +
                 "      { \"-name\": \"lowvideo\" },\n" +
                 "      { \"-name\": \"lsearch\" },\n" +
                 "      {\n" +
                 "        \"-name\": \"lseek\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"off_t\",\n" +
                 "          \"Param\": [\n" +
                 "            { \"-name\": \"int filedes\" },\n" +
                 "            { \"-name\": \"off_t offset\" },\n" +
                 "            { \"-name\": \"int whence\" }\n" +
                 "          ]\n" +
                 "        }\n" +
                 "      },\n" +
                 "      { \"-name\": \"lt\" },\n" +
                 "      { \"-name\": \"ltoa\" },\n" +
                 "      { \"-name\": \"make_heap\" },\n" +
                 "      { \"-name\": \"make_pair\" },\n" +
                 "      {\n" +
                 "        \"-name\": \"malloc\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"void\",\n" +
                 "          \"Param\": { \"-name\": \"size_t size\" }\n" +
                 "        }\n" +
                 "      },\n" +
                 "      { \"-name\": \"map\" },\n" +
                 "      { \"-name\": \"matherr\" },\n" +
                 "      { \"-name\": \"max\" },\n" +
                 "      { \"-name\": \"max_element\" },\n" +
                 "      { \"-name\": \"max_exponent\" },\n" +
                 "      { \"-name\": \"max_exponent10\" },\n" +
                 "      { \"-name\": \"max_length\" },\n" +
                 "      { \"-name\": \"max_size\" },\n" +
                 "      {\n" +
                 "        \"-name\": \"mblen\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"int\",\n" +
                 "          \"Param\": [\n" +
                 "            { \"-name\": \"const char *s\" },\n" +
                 "            { \"-name\": \"size_t n\" }\n" +
                 "          ]\n" +
                 "        }\n" +
                 "      },\n" +
                 "      {\n" +
                 "        \"-name\": \"mbstowcs\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"size_t\",\n" +
                 "          \"Param\": [\n" +
                 "            { \"-name\": \"wchar_t *pwcs\" },\n" +
                 "            { \"-name\": \"const char *s\" },\n" +
                 "            { \"-name\": \"size_t n\" }\n" +
                 "          ]\n" +
                 "        }\n" +
                 "      },\n" +
                 "      {\n" +
                 "        \"-name\": \"mbtowc\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"int\",\n" +
                 "          \"Param\": [\n" +
                 "            { \"-name\": \"wchar_t *pwc\" },\n" +
                 "            { \"-name\": \"const char *s\" },\n" +
                 "            { \"-name\": \"size_t n\" }\n" +
                 "          ]\n" +
                 "        }\n" +
                 "      },\n" +
                 "      { \"-name\": \"memccpy\" },\n" +
                 "      {\n" +
                 "        \"-name\": \"memchr\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"void *\",\n" +
                 "          \"Param\": [\n" +
                 "            { \"-name\": \"const void *s\" },\n" +
                 "            { \"-name\": \"int c\" },\n" +
                 "            { \"-name\": \"size_t n\" }\n" +
                 "          ]\n" +
                 "        }\n" +
                 "      },\n" +
                 "      {\n" +
                 "        \"-name\": \"memcmp\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"int\",\n" +
                 "          \"Param\": [\n" +
                 "            { \"-name\": \"const void *s1\" },\n" +
                 "            { \"-name\": \"const void *s2\" },\n" +
                 "            { \"-name\": \"size_t n\" }\n" +
                 "          ]\n" +
                 "        }\n" +
                 "      },\n" +
                 "      {\n" +
                 "        \"-name\": \"memcpy\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"void *\",\n" +
                 "          \"Param\": [\n" +
                 "            { \"-name\": \"void *s1\" },\n" +
                 "            { \"-name\": \"const void *s2\" },\n" +
                 "            { \"-name\": \"size_t n\" }\n" +
                 "          ]\n" +
                 "        }\n" +
                 "      },\n" +
                 "      { \"-name\": \"memicmp\" },\n" +
                 "      {\n" +
                 "        \"-name\": \"memmove\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"void *\",\n" +
                 "          \"Param\": [\n" +
                 "            { \"-name\": \"void * s1\" },\n" +
                 "            { \"-name\": \"const void *s2\" },\n" +
                 "            { \"-name\": \"size_t n\" }\n" +
                 "          ]\n" +
                 "        }\n" +
                 "      },\n" +
                 "      {\n" +
                 "        \"-name\": \"memset\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"void *\",\n" +
                 "          \"Param\": [\n" +
                 "            { \"-name\": \"void *s\" },\n" +
                 "            { \"-name\": \"int c\" },\n" +
                 "            { \"-name\": \"size_t n\" }\n" +
                 "          ]\n" +
                 "        }\n" +
                 "      },\n" +
                 "      { \"-name\": \"merge\" },\n" +
                 "      { \"-name\": \"min\" },\n" +
                 "      { \"-name\": \"minus\" },\n" +
                 "      { \"-name\": \"min_element\" },\n" +
                 "      { \"-name\": \"min_exponent\" },\n" +
                 "      { \"-name\": \"min_exponent10\" },\n" +
                 "      { \"-name\": \"mismatch\" },\n" +
                 "      {\n" +
                 "        \"-name\": \"mkdir\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"int\",\n" +
                 "          \"Param\": [\n" +
                 "            { \"-name\": \"const char *path\" },\n" +
                 "            { \"-name\": \"mode_t mode\" }\n" +
                 "          ]\n" +
                 "        }\n" +
                 "      },\n" +
                 "      { \"-name\": \"mktemp\" },\n" +
                 "      {\n" +
                 "        \"-name\": \"mktime\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"time_t\",\n" +
                 "          \"Param\": { \"-name\": \"struct tm *timer\" }\n" +
                 "        }\n" +
                 "      },\n" +
                 "      { \"-name\": \"MK_FP\" },\n" +
                 "      {\n" +
                 "        \"-name\": \"modf\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"double\",\n" +
                 "          \"Param\": [\n" +
                 "            { \"-name\": \"double value\" },\n" +
                 "            { \"-name\": \"double *iptr\" }\n" +
                 "          ]\n" +
                 "        }\n" +
                 "      },\n" +
                 "      { \"-name\": \"modfl\" },\n" +
                 "      { \"-name\": \"modulus\" },\n" +
                 "      { \"-name\": \"move\" },\n" +
                 "      { \"-name\": \"movedata\" },\n" +
                 "      { \"-name\": \"moverel\" },\n" +
                 "      { \"-name\": \"movetext\" },\n" +
                 "      { \"-name\": \"moveto\" },\n" +
                 "      { \"-name\": \"movmem\" },\n" +
                 "      { \"-name\": \"multimap\" },\n" +
                 "      { \"-name\": \"multiset\" },\n" +
                 "      { \"-name\": \"mutable\" },\n" +
                 "      { \"-name\": \"name\" },\n" +
                 "      { \"-name\": \"namespace\" },\n" +
                 "      { \"-name\": \"narrow\" },\n" +
                 "      { \"-name\": \"negate\" },\n" +
                 "      { \"-name\": \"negative_sign\" },\n" +
                 "      { \"-name\": \"neg_format\" },\n" +
                 "      { \"-name\": \"new\" },\n" +
                 "      { \"-name\": \"next_permutation\" },\n" +
                 "      { \"-name\": \"noboolalpha\" },\n" +
                 "      { \"-name\": \"none\" },\n" +
                 "      { \"-name\": \"norm\" },\n" +
                 "      { \"-name\": \"normvideo\" },\n" +
                 "      { \"-name\": \"noshowbase\" },\n" +
                 "      { \"-name\": \"noshowpoint\" },\n" +
                 "      { \"-name\": \"noshowpos\" },\n" +
                 "      { \"-name\": \"noskipws\" },\n" +
                 "      { \"-name\": \"nosound\" },\n" +
                 "      { \"-name\": \"not1\" },\n" +
                 "      { \"-name\": \"not2\" },\n" +
                 "      { \"-name\": \"not_eof\" },\n" +
                 "      { \"-name\": \"not_equal_to\" },\n" +
                 "      { \"-name\": \"nounitbuf\" },\n" +
                 "      { \"-name\": \"nouppercase\" },\n" +
                 "      { \"-name\": \"nth_element\" },\n" +
                 "      { \"-name\": \"numeric_limits\" },\n" +
                 "      { \"-name\": \"oct\" },\n" +
                 "      { \"-name\": \"off_type\" },\n" +
                 "      { \"-name\": \"ofstream\" },\n" +
                 "      {\n" +
                 "        \"-name\": \"open\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"int\",\n" +
                 "          \"Param\": [\n" +
                 "            { \"-name\": \"const char *path\" },\n" +
                 "            { \"-name\": \"int oflag\" },\n" +
                 "            { \"-name\": \"...\" }\n" +
                 "          ]\n" +
                 "        }\n" +
                 "      },\n" +
                 "      {\n" +
                 "        \"-name\": \"opendir\",\n" +
                 "        \"-func\": \"yes\",\n" +
                 "        \"Overload\": {\n" +
                 "          \"-retVal\": \"DIR *\",\n" +
                 "          \"Param\": { \"-name\": \"const char *dirname\" }\n" +
                 "        }\n" +
                 "      },\n" +
                 "      { \"-name\": \"openmode\" },\n" +
                 "      { \"-name\": \"operator!\" },\n" +
                 "      { \"-name\": \"operator!=\" },\n" +
                 "      { \"-name\": \"operator\" }\n" +
                 "    ]\n" +
                 "  }";
        try {
             cppJson = new JSONObject(cppJsonString);
        } catch (JSONException e) {
            e.printStackTrace();
        }



    }

    @Override
    public void onCurrentInputMethodSubtypeChanged(InputMethodSubtype subtype) {
        mInputView.setSubtypeOnSpaceKey(subtype);
    }

    /**
     * Deal with the editor reporting movement of its cursor.
     */
    @Override public void onUpdateSelection(int oldSelStart, int oldSelEnd,
            int newSelStart, int newSelEnd,
            int candidatesStart, int candidatesEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd,
                candidatesStart, candidatesEnd);
        
        // If the current selection in the text view changes, we should
        // clear whatever candidate text we have.
        if (mComposing.length() > 0 && (newSelStart != candidatesEnd
                || newSelEnd != candidatesEnd)) {
            mComposing.setLength(0);
            updateCandidates();
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) {
                ic.finishComposingText();
            }
        }
    }

    /**
     * This tells us about completions that the editor has determined based
     * on the current text in it.  We want to use this in fullscreen mode
     * to show the completions ourself, since the editor can not be seen
     * in that situation.
     */
    @Override public void onDisplayCompletions(CompletionInfo[] completions) {
        if (mCompletionOn) {

            mCompletions = completions;
            if (completions == null) {
                setSuggestions(null, false, false);
                return;
            }
            
            List<String> stringList = new ArrayList<String>();
            for (int i = 0; i < completions.length; i++) {
                CompletionInfo ci = completions[i];
                if (ci != null) stringList.add(ci.getText().toString());
            }

            //setSuggestions(stringList, true, true);
            setSuggestions(stringList, true, true);
        }

    }
    
    /**
     * This translates incoming hard key events in to edit operations on an
     * InputConnection.  It is only needed when using the
     * PROCESS_HARD_KEYS option.
     */
    private boolean translateKeyDown(int keyCode, KeyEvent event) {
        mMetaState = MetaKeyKeyListener.handleKeyDown(mMetaState,
                keyCode, event);
        int c = event.getUnicodeChar(MetaKeyKeyListener.getMetaState(mMetaState));
        mMetaState = MetaKeyKeyListener.adjustMetaAfterKeypress(mMetaState);
        InputConnection ic = getCurrentInputConnection();
        if (c == 0 || ic == null) {
            return false;
        }
        
        boolean dead = false;

        if ((c & KeyCharacterMap.COMBINING_ACCENT) != 0) {
            dead = true;
            c = c & KeyCharacterMap.COMBINING_ACCENT_MASK;
        }
        
        if (mComposing.length() > 0) {
            char accent = mComposing.charAt(mComposing.length() -1 );
            int composed = KeyEvent.getDeadChar(accent, c);

            if (composed != 0) {
                c = composed;
                mComposing.setLength(mComposing.length()-1);
            }
        }
        
        onKey(c, null);
        
        return true;
    }
    
    /**
     * Use this to monitor key events being delivered to the application.
     * We get first crack at them, and can either resume them or let them
     * continue to the app.
     */
    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BACK:
                // The InputMethodService already takes care of the back
                // key for us, to dismiss the input method if it is shown.
                // However, our keyboard could be showing a pop-up window
                // that back should dismiss, so we first allow it to do that.
                if (event.getRepeatCount() == 0 && mInputView != null) {
                    if (mInputView.handleBack()) {
                        return true;
                    }
                }
                break;
                
            case KeyEvent.KEYCODE_DEL:
                // Special handling of the delete key: if we currently are
                // composing text for the user, we want to modify that instead
                // of let the application to the delete itself.
                if (mComposing.length() > 0) {
                    onKey(Keyboard.KEYCODE_DELETE, null);
                    return true;
                }
                break;
                
            case KeyEvent.KEYCODE_ENTER:
                // Let the underlying text editor always handle these.
                return false;
                
            default:
                // For all other keys, if we want to do transformations on
                // text being entered with a hard keyboard, we need to process
                // it and do the appropriate action.
                if (PROCESS_HARD_KEYS) {
                    if (keyCode == KeyEvent.KEYCODE_SPACE
                            && (event.getMetaState()&KeyEvent.META_ALT_ON) != 0) {
                        // A silly example: in our input method, Alt+Space
                        // is a shortcut for 'android' in lower case.
                        InputConnection ic = getCurrentInputConnection();
                        if (ic != null) {
                            // First, tell the editor that it is no longer in the
                            // shift state, since we are consuming this.
                            ic.clearMetaKeyStates(KeyEvent.META_ALT_ON);
                            keyDownUp(KeyEvent.KEYCODE_A);
                            keyDownUp(KeyEvent.KEYCODE_N);
                            keyDownUp(KeyEvent.KEYCODE_D);
                            keyDownUp(KeyEvent.KEYCODE_R);
                            keyDownUp(KeyEvent.KEYCODE_O);
                            keyDownUp(KeyEvent.KEYCODE_I);
                            keyDownUp(KeyEvent.KEYCODE_D);
                            // And we consume this event.
                            return true;
                        }
                    }
                    if (mPredictionOn && translateKeyDown(keyCode, event)) {
                        return true;
                    }
                }
        }
        
        return super.onKeyDown(keyCode, event);
    }

    /**
     * Use this to monitor key events being delivered to the application.
     * We get first crack at them, and can either resume them or let them
     * continue to the app.
     */
    @Override public boolean onKeyUp(int keyCode, KeyEvent event) {
        // If we want to do transformations on text being entered with a hard
        // keyboard, we need to process the up events to update the meta key
        // state we are tracking.
        if (PROCESS_HARD_KEYS) {
            if (mPredictionOn) {
                mMetaState = MetaKeyKeyListener.handleKeyUp(mMetaState,
                        keyCode, event);
            }
        }
        
        return super.onKeyUp(keyCode, event);
    }

    /**
     * Helper function to commit any text being composed in to the editor.
     */
    private void commitTyped(InputConnection inputConnection) {
        if (mComposing.length() > 0) {
            inputConnection.commitText(mComposing, mComposing.length());
            mComposing.setLength(0);
            updateCandidates();
        }
    }

    private void commitTyped(InputConnection inputConnection, String suggested) {
        if (mComposing.length() > 0) {
            inputConnection.commitText(suggested, suggested.length());
            mComposing.setLength(0);
            updateCandidates();
        }
    }



    /**
     * Helper to update the shift state of our keyboard based on the initial
     * editor state.
     */
    private void updateShiftKeyState(EditorInfo attr) {
        if (attr != null
                && mInputView != null && mQwertyKeyboard == mInputView.getKeyboard()) {
            int caps = 0;
            EditorInfo ei = getCurrentInputEditorInfo();
            if (ei != null && ei.inputType != InputType.TYPE_NULL) {
                caps = getCurrentInputConnection().getCursorCapsMode(attr.inputType);
            }
            mInputView.setShifted(mCapsLock || caps != 0);
        }
    }

    /**
     * Helper to determine if a given character code is alphabetic.
     */
    private boolean isAlphabet(int code) {
        if (Character.isLetter(code)) {
            return true;
        } else {
            return false;
        }
    }
    
    /**
     * Helper to send a key down / key up pair to the current editor.
     */
    private void keyDownUp(int keyEventCode) {
        getCurrentInputConnection().sendKeyEvent(
                new KeyEvent(KeyEvent.ACTION_DOWN, keyEventCode));
        getCurrentInputConnection().sendKeyEvent(
                new KeyEvent(KeyEvent.ACTION_UP, keyEventCode));
    }
    
    /**
     * Helper to send a character to the editor as raw key events.
     */
    private void sendKey(int keyCode) {
        switch (keyCode) {
            case '\n':
                keyDownUp(KeyEvent.KEYCODE_ENTER);
                break;
            default:
                if (keyCode >= '0' && keyCode <= '9') {
                    keyDownUp(keyCode - '0' + KeyEvent.KEYCODE_0);
                } else {
                    getCurrentInputConnection().commitText(String.valueOf((char) keyCode), 1);
                }
                break;
        }
    }

    // Implementation of KeyboardViewListener

    public void onKey(int primaryCode, int[] keyCodes) {
        if (isWordSeparator(primaryCode)) {
            // Handle separator
            if (mComposing.length() > 0) {
                commitTyped(getCurrentInputConnection());
            }
            sendKey(primaryCode);
            updateShiftKeyState(getCurrentInputEditorInfo());
        } else if (primaryCode == Keyboard.KEYCODE_DELETE) {
            handleBackspace();
        } else if (primaryCode == Keyboard.KEYCODE_SHIFT) {
            handleShift();
        } else if (primaryCode == Keyboard.KEYCODE_CANCEL) {
            handleClose();
            return;
        } else if (primaryCode == LatinKeyboardView.KEYCODE_OPTIONS) {
            // Show a menu or somethin'
        } else if (primaryCode == Keyboard.KEYCODE_MODE_CHANGE
                && mInputView != null) {
            Keyboard current = mInputView.getKeyboard();
            if (current == mRussianKeyboard) {
                current = mQwertyKeyboard;
            } else {
                current = mRussianKeyboard;
            }
            mInputView.setKeyboard(current);
            if (current == mSymbolsKeyboard) {
                current.setShifted(false);
            }
        } else {
            handleCharacter(primaryCode, keyCodes);
        }
    }

    public void onText(CharSequence text) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        ic.beginBatchEdit();
        if (mComposing.length() > 0) {
            commitTyped(ic);
        }
        ic.commitText(text, 0);
        ic.endBatchEdit();
        updateShiftKeyState(getCurrentInputEditorInfo());
    }

    /**
     * Update the list of available candidates from the current composing
     * text.  This will need to be filled in by however you are determining
     * candidates.
     */
    private void updateCandidates() {
        if (!mCompletionOn) {
            if (mComposing.length() > 0) {
                ArrayList<String> list = new ArrayList<String>();
                list.add(mComposing.toString());
                setSuggestions(list, true, true);
            } else {
                setSuggestions(null, false, false);
            }
        }
    }
    
    public void setSuggestions(List<String> suggestions, boolean completions,
            boolean typedWordValid) {
        if (suggestions != null && suggestions.size() > 0) {
            setCandidatesViewShown(true);
        } else if (isExtractViewShown()) {
            setCandidatesViewShown(true);
        }
        if (mCandidateView != null) {
            if(suggestions!=null) {
                // suggestions.add("ololo");
                // suggestions.add("azazaz");
                // suggestions.add("wtf");}
                List<String> wordGeomPar=cppSujjest(mComposing.toString());
                if(wordGeomPar!=null){
                    suggestions.addAll(wordGeomPar);
                }

            }
            mCandidateView.setSuggestions(suggestions, completions, typedWordValid);
        }
    }

    List<String> cppSujjest(String typed){
        List<String> ls = new ArrayList<String>();
        JSONArray keywords = null;
        JSONObject temp = null;
        String name = null;
        int count = 0;
        try {
            keywords = cppJson.getJSONArray("KeyWord");
            for(int i=0;i<keywords.length()&&count<7;i++){
                temp = (JSONObject) keywords.get(i);
                name = temp.getString("-name");
                   boolean olo = name.matches(".?"+typed.toLowerCase()+"\\w*");
                if(olo)
                {
                    ls.add(name);
                    count++;
                }



            }




        } catch (JSONException e) {
            e.printStackTrace();
        }



        return ls;
    }

    List<String> geomSuggestions(String typed){
        char[] cTyped = typed.toLowerCase().toCharArray();
        List<String> ls = null;
        JSONObject Keygeometry = null;
        try {
             Keygeometry = new JSONObject("{  \"keyboard\": {    \"q\": {      \"x\": \"0.0\",      \"y\": \"0.0\"    },    \"w\": {      \"x\": \"1.0\",      \"y\": \"0.0\"    },    \"e\": {      \"x\": \"2.0\",      \"y\": \"0.0\"    },    \"r\": {      \"x\": \"3.0\",      \"y\": \"0.0\"    },    \"t\": {      \"x\": \"4.0\",      \"y\": \"0.0\"    },    \"y\": {      \"x\": \"5.0\",      \"y\": \"0.0\"    },    \"u\": {      \"x\": \"6.0\",      \"y\": \"0.0\"    },    \"i\": {      \"x\": \"7.0\",      \"y\": \"0.0\"    },    \"o\": {      \"x\": \"8.0\",      \"y\": \"0.0\"    },    \"p\": {      \"x\": \"9.0\",      \"y\": \"0.0\"    },    \"a\": {      \"x\": \"0.5\",      \"y\": \"1.0\"    },    \"s\": {      \"x\": \"1.5\",      \"y\": \"1.0\"    },    \"d\": {      \"x\": \"2.5\",      \"y\": \"1.0\"    },    \"f\": {      \"x\": \"3.5\",      \"y\": \"1.0\"    },    \"g\": {      \"x\": \"4.5\",      \"y\": \"1.0\"    },    \"h\": {      \"x\": \"5.5\",      \"y\": \"1.0\"    },    \"j\": {      \"x\": \"6.5\",      \"y\": \"1.0\"    },    \"k\": {      \"x\": \"7.5\",      \"y\": \"1.0\"    },    \"l\": {      \"x\": \"8.5\",      \"y\": \"1.0\"    },    \"z\": {      \"x\": \"1.5\",      \"y\": \"2.0\"    },    \"x\": {      \"x\": \"2.5\",      \"y\": \"2.0\"    },    \"c\": {      \"x\": \"3.5\",      \"y\": \"2.0\"    },    \"v\": {      \"x\": \"4.5\",      \"y\": \"2.0\"    },    \"b\": {      \"x\": \"5.5\",      \"y\": \"2.0\"    },    \"n\": {      \"x\": \"6.5\",      \"y\": \"2.0\"    },    \"m\": {      \"x\": \"7.5\",      \"y\": \"2.0\"    }  }}");
        } catch (JSONException e) {
            e.printStackTrace();
        }
        GeometryFactory gf = new GeometryFactory(new PrecisionModel(0.001));
        JSONObject Keyboard = null;
        JSONObject key = null;
        double x = 0;
        double y = 0;
        String coord = null;
        Coordinate[] coordinates =new Coordinate[typed.length()+1];
        if (typed.length()>3) {
            for (int i = 0; i < typed.length(); i++) {
                if (Keygeometry != null) {
                    try {

                        Keyboard = Keygeometry.getJSONObject("keyboard");
                        key = Keyboard.getJSONObject(Character.toString(cTyped[i]));
                        x = key.getDouble("x");
                        y = key.getDouble("y");
                        coord += "("+x+","+y+"),";
                        Coordinate cTemp = new Coordinate(x, y);
                        coordinates[i] = cTemp;
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }

                }
            }
            coordinates[typed.length()] = coordinates[0];
            Log.d("Coordinates",coord) ;
            LinearRing[] empty = new LinearRing[0];

            LinearRing geomWord = gf.createLinearRing(coordinates);
            Polygon pl = gf.createPolygon(geomWord);

            ls = new ArrayList<String>();
            ls.add("" + pl.getArea());
            ls.add("" + pl.getLength());
        }

        return ls;

    }


    private void handleBackspace() {
        final int length = mComposing.length();
        if (length > 1) {
            mComposing.delete(length - 1, length);
            getCurrentInputConnection().setComposingText(mComposing, 1);
            updateCandidates();
        } else if (length > 0) {
            mComposing.setLength(0);
            getCurrentInputConnection().commitText("", 0);
            updateCandidates();
        } else {
            keyDownUp(KeyEvent.KEYCODE_DEL);
        }
        updateShiftKeyState(getCurrentInputEditorInfo());
    }

    private void handleShift() {
        if (mInputView == null) {
            return;
        }
        
        Keyboard currentKeyboard = mInputView.getKeyboard();
        if (mQwertyKeyboard == currentKeyboard) {
            // Alphabet keyboard
            checkToggleCapsLock();
            mInputView.setShifted(mCapsLock || !mInputView.isShifted());
        } else if (currentKeyboard == mSymbolsKeyboard) {
            mSymbolsKeyboard.setShifted(true);
            mInputView.setKeyboard(mSymbolsShiftedKeyboard);
            mSymbolsShiftedKeyboard.setShifted(true);
        } else if (currentKeyboard == mSymbolsShiftedKeyboard) {
            mSymbolsShiftedKeyboard.setShifted(false);
            mInputView.setKeyboard(mSymbolsKeyboard);
            mSymbolsKeyboard.setShifted(false);
        }
    }
    
    private void handleCharacter(int primaryCode, int[] keyCodes) {
        if (isInputViewShown()) {
            if (mInputView.isShifted()) {
                primaryCode = Character.toUpperCase(primaryCode);
            }
        }
        if (isAlphabet(primaryCode) && mPredictionOn) {
            mComposing.append((char) primaryCode);
            getCurrentInputConnection().setComposingText(mComposing, 1);
            updateShiftKeyState(getCurrentInputEditorInfo());
            updateCandidates();
        } else {
            getCurrentInputConnection().commitText(
                    String.valueOf((char) primaryCode), 1);
        }
    }

    private void handleClose() {
        commitTyped(getCurrentInputConnection());
        requestHideSelf(0);
        mInputView.closing();
    }

    private void checkToggleCapsLock() {
        long now = System.currentTimeMillis();
        if (mLastShiftTime + 800 > now) {
            mCapsLock = !mCapsLock;
            mLastShiftTime = 0;
        } else {
            mLastShiftTime = now;
        }
    }
    
    private String getWordSeparators() {
        return mWordSeparators;
    }
    
    public boolean isWordSeparator(int code) {
        String separators = getWordSeparators();
        return separators.contains(String.valueOf((char)code));
    }

    public void pickDefaultCandidate() {
        pickSuggestionManually(0);
    }
    
    public void pickSuggestionManually(int index) {
        if (mCompletionOn && mCompletions != null && index >= 0
                && index < mCompletions.length) {
            CompletionInfo ci = mCompletions[index];
            getCurrentInputConnection().commitCompletion(ci);
            if (mCandidateView != null) {
                mCandidateView.clear();
            }
            updateShiftKeyState(getCurrentInputEditorInfo());
        } else if (mComposing.length() > 0) {
            // If we were generating candidate suggestions for the current
            // text, we would commit one of them here.  But for this sample,
            // we will just commit the current text.

            commitTyped(getCurrentInputConnection(),mCandidateView.mSuggestions.get(index));
        }
    }
    
    public void swipeRight() {
        if (mCompletionOn) {
            pickDefaultCandidate();
        }
    }
    
    public void swipeLeft() {
        handleBackspace();
    }

    public void swipeDown() {
        handleClose();
    }

    public void swipeUp() {
    }
    
    public void onPress(int primaryCode) {
    }
    
    public void onRelease(int primaryCode) {
    }
}
