/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 1997-2017 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

package com.sun.faces.renderkit;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.faces.FacesException;
import javax.faces.context.FacesContext;

import java.security.SecureRandom;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.sun.faces.util.FacesLogger;
import com.sun.faces.RIConstants;

import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.SecretKeySpec;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.servlet.http.HttpSession;
import javax.xml.bind.DatatypeConverter;

/**
 * <p>This utility class is to provide both encryption and
 * decryption <code>Ciphers</code> to <code>ResponseStateManager</code>
 * implementations wishing to provide encryption support.</p>
 * 
 * <p>The algorithm used to encrypt byte array is AES with CBC.</p>
 *  
 * <p>Original author Inderjeet Singh, J2EE Blue Prints Team. Modified to suit JSF
 * needs.</p> 
 */
public final class ByteArrayGuard {


     // Log instance for this class
    private static final Logger LOGGER = FacesLogger.RENDERKIT.getLogger();

    private static final int MAC_LENGTH = 32;
    private static final int KEY_LENGTH = 128;
    private static final int IV_LENGTH = 16;

    private static final String KEY_ALGORITHM = "AES";
    private static final String CIPHER_CODE = "AES/CBC/PKCS5Padding";
    private static final String MAC_CODE = "HmacSHA256";
    private static final String SK_SESSION_KEY = RIConstants.FACES_PREFIX + "SK"; 
    private SecretKey sk;

    // ------------------------------------------------------------ Constructors

    public ByteArrayGuard() {

        try {
            setupKeyAndMac();
        } catch (Exception e) {
            if (LOGGER.isLoggable(Level.SEVERE)) { 
                LOGGER.log(Level.SEVERE,
                           "Unexpected exception initializing encryption."
                           + "  No encryption will be performed.",
                           e);
            }
            System.err.println("ERROR: Initializing Ciphers");
        }
    }

    // ---------------------------------------------------------- Public Methods    

    /**
     * This method:
     *    Encrypts bytes using a cipher.  
     *    Generates MAC for intialization vector of the cipher
     *    Generates MAC for encrypted data
     *    Returns a byte array consisting of the following concatenated together:
     *       |MAC for cnrypted Data | MAC for Init Vector | Encrypted Data |
     * @param bytes The byte array to be encrypted.
     * @return the encrypted byte array.
     */
    public byte[] encrypt(FacesContext facesContext, byte[] bytes) {
        byte[] securedata = null;
        try {
            // Generate IV
            SecureRandom rand = new SecureRandom();
            byte[] iv = new byte[16];
            rand.nextBytes(iv);
            IvParameterSpec ivspec = new IvParameterSpec(iv);
            Cipher encryptCipher = Cipher.getInstance(CIPHER_CODE);
            SecretKey secKey = getSecretKey(facesContext);
            encryptCipher.init(Cipher.ENCRYPT_MODE, secKey, ivspec);
            Mac encryptMac = Mac.getInstance(MAC_CODE);
            encryptMac.init(secKey);
            encryptMac.update(iv);
            // encrypt the plaintext
            byte[] encdata = encryptCipher.doFinal(bytes);
            byte[] macBytes = encryptMac.doFinal(encdata);
            byte[] tmp = concatBytes(macBytes, iv);
            securedata = concatBytes(tmp, encdata);
        } catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException | InvalidAlgorithmParameterException | IllegalStateException | IllegalBlockSizeException | BadPaddingException e) {
            if (LOGGER.isLoggable(Level.SEVERE)) {
                LOGGER.log(Level.SEVERE,
                           "Unexpected exception initializing encryption."
                           + "  No encryption will be performed.",
                           e);
            }
            return null;
        }
        return securedata;
    }


    /**
     * This method decrypts the provided byte array.
     * The decryption is only performed if the regenerated MAC
     * is the same as the MAC for the received value.
     * @param bytes Encrypted byte array to be decrypted.
     * @return Decrypted byte array.
     */
    public byte[] decrypt(FacesContext facesContext, byte[] bytes) {
        try {
            // Extract MAC
            byte[] macBytes = new byte[MAC_LENGTH];
            System.arraycopy(bytes, 0, macBytes, 0, macBytes.length);

            // Extract IV
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(bytes, macBytes.length, iv, 0, iv.length);

            // Extract encrypted data
            byte[] encdata = new byte[bytes.length - macBytes.length - iv.length];
            System.arraycopy(bytes, macBytes.length + iv.length, encdata, 0, encdata.length);

            IvParameterSpec ivspec = new IvParameterSpec(iv);
            SecretKey secKey =  getSecretKey(facesContext);
            Cipher decryptCipher = Cipher.getInstance(CIPHER_CODE);
            decryptCipher.init(Cipher.DECRYPT_MODE, secKey, ivspec);

            // verify MAC by regenerating it and comparing it with the received value
            Mac decryptMac = Mac.getInstance(MAC_CODE);
            decryptMac.init(secKey);
            decryptMac.update(iv);
            decryptMac.update(encdata);
            byte[] macBytesCalculated = decryptMac.doFinal();
            if (areArrayEqualsConstantTime(macBytes, macBytesCalculated)) {
                // continue only if the MAC was valid
                // System.out.println("Valid MAC found!");
                byte[] plaindata = decryptCipher.doFinal(encdata);
                return plaindata;
            } else {
                System.err.println("ERROR: MAC did not verify!");
                return null;
            }
        } catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException | InvalidAlgorithmParameterException | IllegalStateException | IllegalBlockSizeException | BadPaddingException e) {
            System.err.println("ERROR: Decrypting:"+e.getCause());
            return null; // Signal to JSF runtime
        }
    }

    private boolean areArrayEqualsConstantTime(byte[] array1, byte[] array2) {
        boolean result = true;
        for(int i=0; i<array1.length; i++) {
            if (array1[i] != array2[i]) {
                result = false;
            }
        }
        return result;
    }

    // --------------------------------------------------------- Private Methods

    /**
     * Generates secret key.
     * Initializes MAC(s).
     */
    private void setupKeyAndMac() {

        /*
         * Lets see if an encoded key was given to the application, if so use
         * it and skip the code to generate it.
         */
        try {
            InitialContext context = new InitialContext();
            String encodedKeyArray = (String) context.lookup("java:comp/env/jsf/ClientSideSecretKey");
            byte[] keyArray = DatatypeConverter.parseBase64Binary(encodedKeyArray);
            sk = new SecretKeySpec(keyArray, KEY_ALGORITHM);
        }
        catch(NamingException exception) {
            if (LOGGER.isLoggable(Level.FINEST)) { 
                LOGGER.log(Level.FINEST, "Unable to find the encoded key.", exception);
            }
        }
        
        if (sk == null) {
            try {
                KeyGenerator kg = KeyGenerator.getInstance(KEY_ALGORITHM);
                kg.init(KEY_LENGTH);   // 256 if you're using the Unlimited Policy Files
                sk = kg.generateKey(); 
//                System.out.print("SecretKey: " + DatatypeConverter.printBase64Binary(sk.getEncoded()));

            } catch (Exception e) {
                throw new FacesException(e);
            }
        }
    }

    /**
     * This method concatenates two byte arrays
     * @return a byte array of array1||array2
     * @param array1 first byte array to be concatenated
     * @param array2 second byte array to be concatenated
     */
    private static byte[] concatBytes(byte[] array1, byte[] array2) {
        byte[] cBytes = new byte[array1.length + array2.length];
        try {
            System.arraycopy(array1, 0, cBytes, 0, array1.length);
            System.arraycopy(array2, 0, cBytes, array1.length, array2.length);
        } catch(Exception e) {
            throw new FacesException(e);
        }
        return cBytes;
    }  
    
    private SecretKey getSecretKey(FacesContext facesContext) {

        SecretKey result = sk;
        Object sessionObj;

        if (null != (sessionObj =
            facesContext.getExternalContext().getSession(false))) {
          // Don't break on portlets.
          if (sessionObj instanceof HttpSession) {
             HttpSession session = (HttpSession)sessionObj;
             result = (SecretKey) session.getAttribute(SK_SESSION_KEY);
             if (null == result) {
                 session.setAttribute(SK_SESSION_KEY, sk);
                 result = sk;
             }
           }
         }
         return result;
      }
}
