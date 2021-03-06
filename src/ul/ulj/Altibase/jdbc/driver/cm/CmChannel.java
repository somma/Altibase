/**
 *  Copyright (c) 1999~2017, Altibase Corp. and/or its affiliates. All rights reserved.
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Lesser General Public License, version 3,
 *  as published by the Free Software Foundation.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *  GNU Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package Altibase.jdbc.driver.cm;

import Altibase.jdbc.driver.AltibaseVersion;
import Altibase.jdbc.driver.ClobReader;
import Altibase.jdbc.driver.LobConst;
import Altibase.jdbc.driver.datatype.ColumnFactory;
import Altibase.jdbc.driver.ex.Error;
import Altibase.jdbc.driver.ex.ErrorDef;
import Altibase.jdbc.driver.logging.DumpByteUtil;
import Altibase.jdbc.driver.logging.LoggingProxy;
import Altibase.jdbc.driver.logging.MultipleFileHandler;
import Altibase.jdbc.driver.logging.TraceFlag;
import Altibase.jdbc.driver.util.*;

import javax.net.ssl.*;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigInteger;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.security.*;
import java.security.cert.CertificateException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.*;
import java.util.regex.Pattern;

public class CmChannel extends CmBufferWriter
{
    static final byte NO_OP = -1;

    private static final int CHANNEL_STATE_CLOSED = 0;
    private static final int CHANNEL_STATE_WRITABLE = 1;
    private static final int CHANNEL_STATE_READABLE = 2;
    private static final String[] CHANNEL_STATE_STRING = {"CHANNEL_STATE_CLOSED", "CHANNEL_STATE_WRITABLE", "CHANNEL_STATE_READABLE"};

    public  static final int   CM_BLOCK_SIZE                   = 32 * 1024;
    public  static final int   CM_PACKET_HEADER_SIZE           = 16;
    private static final byte  CM_PACKET_HEADER_VERSION        = AltibaseVersion.CM_MAJOR_VERSION;
    private static final byte  CM_PACKET_HEADER_RESERVED_BYTE  = 0;
    private static final short CM_PACKET_HEADER_RESERVED_SHORT = 0;
    private static final int   CM_PACKET_HEADER_RESERVED_INT   = 0;
    private static final int   CHAR_VARCHAR_COLUMN_SIZE        = 32000;
    public static final int    INVALID_SOCKTFD                 = -1;

    private static final Pattern IP_PATTERN = Pattern.compile("(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}|\\[[^\\]]+\\])");

    // PROJ-2474 keystore파일을 접근하는 기본 프로토콜은 file:이다.
    private static final String DEFAULT_URL_FILE_SCHEME = "file:";
    private static final String DEFAULT_KEYSTORE_TYPE = "JKS";

    // PROJ-2583 jdbc logging
    private static final String JRE_DEFAULT_VERSION = "1.4.";

    private short mSessionID = 0;

    private int mState;
    private Socket mSocket;
    private int mSocketFD = INVALID_SOCKTFD; // PROJ-2625
    private CharBuffer mCharBuf;
    private byte[] mLobBuf;
    private WritableByteChannel mWriteChannel;
    private ReadableByteChannel mReadChannel;
    private AltibaseReadableCharChannel mReadChannel4Clob;
    private ColumnFactory mColumnFactory = new ColumnFactory();

    /* packet header */
    private short mPayloadSize = 0;
    private boolean mIsLastPacket = true;
    private boolean mIsRedundantPacket = false;

    private ByteBuffer mPrimitiveTypeBuf;
    private static final int MAX_PRIMITIVE_TYPE_SIZE = 8;

    private int mNextSendSeqNo = 0;
    private int mPrevReceivedSeqNo = -1;

    private CharsetDecoder mDBDecoder = CharsetUtils.newAsciiDecoder();
    private CharsetDecoder mNCharDecoder = CharsetUtils.newAsciiDecoder();

    private boolean mIsPreferIPv6 = false;

    private boolean mRemoveRedundantMode = false;

    private int mPendedDataLength;
    private int CLOB_BUF_SIZE;

    private int mResponseTimeout;

    private int mLobCacheThreshold;

    // PROJ-2474 SSL이 활성화 되어있는지 나타내는 flag
    private boolean mUseSsl;
    // PROJ-2474 SSL 관련된 속성들을 가지고 있는 객체
    private SSLProperties mSslProps;
    private transient Logger mCmLogger;

    // PROJ-2625 Semi-async Prefetch, Prefetch Auto-tuning
    public static final int CM_DEFAULT_SNDBUF_SIZE = CM_BLOCK_SIZE * 2;
    public static final int CM_DEFAULT_RCVBUF_SIZE = CM_BLOCK_SIZE * 2;

    private int mSockRcvBufSize = CM_DEFAULT_RCVBUF_SIZE;

    private CmProtocolContext mAsyncContext;

    // Channel for Target
    private class AltibaseReadableCharChannel implements ReadableCharChannel
    {
        private final CharBuffer mCharBuf  = CharBuffer.allocate(LobConst.LOB_BUFFER_SIZE);
        private boolean          mIsOpen;
        private CoderResult      mResult;
        private Reader           mSrcReader;
        private char[]           mSrcBuf;
        private int              mSrcBufPos;
        private int              mSrcBufLimit;

        private int              mUserReadableLength = -1;
        private int              mTotalActualCharReadLength = 0;

        public void open(Reader aSrc) throws IOException
        {
            close();

            mSrcReader = aSrc;
            mCharBuf.clear();
            mIsOpen = true;
        }

        public void open(Reader aSrc, int aUserReadableLength) throws IOException
        {
            close();

            mSrcReader = aSrc;
            mCharBuf.clear();
            mIsOpen = true;
            mUserReadableLength = aUserReadableLength;
        }

        public void open(char[] aSrc) throws IOException
        {
            open(aSrc, 0, aSrc.length);
        }

        public void open(char[] aSrc, int aOffset, int aLength) throws IOException
        {
            close();

            mSrcBuf = aSrc;
            mSrcBufPos = aOffset;
            mSrcBufLimit = aOffset + aLength;
            mCharBuf.clear();
            mIsOpen = true;
        }

        public int read(ByteBuffer aDst) throws IOException
        {
            if (isClosed())
            {
                Error.throwIOException(ErrorDef.STREAM_ALREADY_CLOSED);
            }

            if (mUserReadableLength > 0)
            {
                // BUG-44553 clob 데이터를 모두 읽었더라도 mCharBuf에 compact된 데이터가 남아 있으면 종료하지 않는다.
                if (mUserReadableLength <= mTotalActualCharReadLength && mCharBuf.position() == 0)
                {
                    return -1;
                }
            }

            mTotalActualCharReadLength += readClobData();

            return copyToSocketBuffer(aDst);
        }

        /**
         * mCharBuf를 encoding한 후 소켓 버퍼로 복사한다.
         * @param aDst 소켓 버퍼
         * @return 실제로 소켓 버퍼로 인코딩되어 복사된 사이즈.
         */
        private int copyToSocketBuffer(ByteBuffer aDst)
        {
            int sReaded = -1;

            if (mCharBuf.flip().remaining() > 0)
            {
                int sBeforeBytePos4Dst = aDst.position();
                mResult = mDBEncoder.encode(mCharBuf, aDst, true);
                sReaded = aDst.position() - sBeforeBytePos4Dst;

                if (mResult.isOverflow())
                {
                    mCharBuf.compact();
                }
                else
                {
                    mCharBuf.clear();
                }
            }

            return sReaded;
        }

        /**
         * Src로부터 clob 데이터를 읽어서 mCharBuf 버퍼에 복사한다.
         * @return 실제로 읽어들인 clob 데이터 사이즈
         * @throws IOException read 도중 I/O 에러가 발생했을 때
         */
        private int readClobData() throws IOException
        {
            int sReadableCharLength = getReadableCharLength();
            if (sReadableCharLength <= 0) // BUG-44553 읽어들일 clob데이터가 남아있을때만 read를 수행
            {
                return 0;
            }

            int sActualCharReadLength = 0;
            if (mSrcBuf == null)
            {
                sActualCharReadLength = mSrcReader.read(mCharBuf.array(), mCharBuf.position(), sReadableCharLength);
                if (sActualCharReadLength > 0)
                {
                    mCharBuf.position(mCharBuf.position() + sActualCharReadLength);
                }
            }
            else
            {
                if (mSrcBufPos < mSrcBufLimit)
                {
                    sActualCharReadLength = Math.min(sReadableCharLength, mSrcBufLimit - mSrcBufPos);
                    mCharBuf.put(mSrcBuf, mSrcBufPos, sActualCharReadLength);
                    mSrcBufPos += sActualCharReadLength;
                }
            }

            return sActualCharReadLength;
        }

        /**
         * clob 데이터를 읽어들일 수 있는 최대 사이즈를 구한다.
         * @return 읽을 수 있는 최대 사이즈
         */
        private int getReadableCharLength()
        {
            int sReadableCharLength;

            if (mUserReadableLength > 0)
            {
                // BUG-44553 실제 남은 양 만큼만 가져오도록 값을 보정
                sReadableCharLength = Math.min(mUserReadableLength - mTotalActualCharReadLength, CLOB_BUF_SIZE);
                sReadableCharLength = Math.min(mCharBuf.remaining(), sReadableCharLength);
            }
            else
            {
                sReadableCharLength = Math.min(mCharBuf.remaining(), CLOB_BUF_SIZE);
            }

            return sReadableCharLength;
        }

        public boolean isOpen()
        {
            return mIsOpen;
        }

        public void close() throws IOException
        {
            if (!isOpen())
            {
                return;
            }

            mUserReadableLength = -1;
            mTotalActualCharReadLength = 0;

            mIsOpen = false;
            mSrcReader = null;
            mSrcBuf = null;
        }

        public Object getSource()
        {
            if (mSrcReader != null)
            {
                return mSrcReader;
            }
            else
            {
                return mSrcBuf;
            }
        }
    }

    public CmChannel()
    {
        mBuffer = ByteBuffer.allocateDirect(CM_BLOCK_SIZE);
        mCharBuf = CharBuffer.allocate(CM_BLOCK_SIZE);
        mCharVarcharColumnBuffer = ByteBuffer.allocate(CHAR_VARCHAR_COLUMN_SIZE);
        mState = CHANNEL_STATE_CLOSED;

        initializeLogger();
    }

    private void initializeLogger()
    {
        if (TraceFlag.TRACE_COMPILE && TraceFlag.TRACE_ENABLED)
        {
            mCmLogger = Logger.getLogger(LoggingProxy.JDBC_LOGGER_CM);
            try
            {
                String sJreVersion = RuntimeEnvironmentVariables.getVariable("java.version", JRE_DEFAULT_VERSION);
                // PROJ-2583 JDK1.4에서는 설정파일에서 handlers를 지정해도 불러오지 못하기 때문에
                // 프로그램적으로 handler를 add해준다.
                if (sJreVersion.startsWith(JRE_DEFAULT_VERSION))
                {
                    String sHandlers = getProperty(MultipleFileHandler.CM_HANDLERS, "");
                    if (sHandlers.contains(MultipleFileHandler.HANDLER_NAME))
                    {
                        mCmLogger.addHandler(new MultipleFileHandler());
                    }
                    else if (sHandlers.contains("java.util.logging.FileHandler"))
                    {
                        String sLocalPattern = getProperty("java.util.logging.FileHandler.pattern", "%h/jdbc_net.log");
                        int sLocalLimit = Integer.parseInt(getProperty("java.util.logging.FileHandler.limit", "10000000"));
                        int sLocalCount = Integer.parseInt(getProperty("java.util.logging.FileHandler.count", "1"));
                        boolean sLocalAppend = Boolean.getBoolean(getProperty("java.util.logging.FileHandler.append", "false"));
                        String sLevel = getProperty("java.util.logging.FileHandler.level", "FINEST");

                        FileHandler sFileHandler = new FileHandler(sLocalPattern, sLocalLimit, sLocalCount, sLocalAppend);
                        sFileHandler.setLevel(Level.parse(sLevel));
                        sFileHandler.setFormatter(new XMLFormatter());
                        mCmLogger.addHandler(sFileHandler);
                    }
                }
                mCmLogger.setUseParentHandlers(false);
            }
            catch (IOException sIOE)
            {
                mCmLogger.log(Level.SEVERE, "Cannot add handler", sIOE);
            }
        }
    }

    private String getProperty(String aName, String aDefaultValue)
    {
        String property = LogManager.getLogManager().getProperty(aName);
        return property != null ? property : aDefaultValue;
    }

    private boolean isPreferIPv6()
    {
        return mIsPreferIPv6;
    }

    public void setPreferredIPv6()
    {
        mIsPreferIPv6 = true;
    }

    private boolean isHostname(String aConnAddr)
    {
        return !IP_PATTERN.matcher(aConnAddr).matches();
    }

    /**
     * Hostname에 대한 IP 목록을 얻는다.
     *
     * @param aHost       IP 목록을 얻을 Hostname
     * @param aPreferIPv6 IP 목록에 IPv6가 먼저 나올지 여부.
     *                    true면 IPv6 다음 IPv4 주소가, false면 IPv4 다음 IPv6 주소 온다.
     *
     * @return Hostname에 대한 IP 목록
     * 
     * @throws SQLException 알 수 없는 Host이거나, IP 목록 조회가 허용되지 않은 경우
     */
    private static List<InetAddress> getAllIpAddressesByName(String aHost, boolean aPreferIPv6) throws SQLException
    {
        InetAddress[] sAddrs = null;

        ArrayList<InetAddress> sIPv4List = new ArrayList<InetAddress>();
        ArrayList<InetAddress> sIPv6List = new ArrayList<InetAddress>();
        ArrayList<InetAddress> sIpAddrList = new ArrayList<InetAddress>();

        try
        {
            sAddrs = InetAddress.getAllByName(aHost);
        }
        catch (Exception sEx)
        {
            Error.throwSQLException(ErrorDef.UNKNOWN_HOST, aHost, sEx);
        }

        for (InetAddress sAddr : sAddrs)
        {
            if (sAddr instanceof Inet4Address)
            {
                sIPv4List.add(sAddr);
            }
            else
            {
                sIPv6List.add(sAddr);
            }
        }

        // To find out in the order of preferred IP address type
        if (aPreferIPv6)
        {
            sIpAddrList.addAll(sIPv6List);
            sIpAddrList.addAll(sIPv4List);
        }
        else
        {
            sIpAddrList.addAll(sIPv4List);
            sIpAddrList.addAll(sIPv6List);
        }

        sIPv6List.clear();
        sIPv4List.clear();

        return sIpAddrList;
    }

    /**
     * 물리 연결을 맺고, CmChannel을 사용하기 위해 준비를 한다.
     * 
     * @param aAddr            접속할 서버의 IP 주소 또는 Hostname
     * @param aBindAddr        소켓에 바인드 할 IP 주소. null이면 바인드 하지 않음.
     * @param aPort            접속할 서버의 Port
     * @param aLoginTimeout    Socket.connect()에 사용할 타임아웃 값 (초 단위)
     * @param aResponseTimeout 소켓 타임아웃 값 (초 단위)
     * 
     * @throws SQLException 이미 연결되어있거나 소켓 생성, 초기화 또는 연결에 실패한 경우
     */
    public void open(String aAddr, String aBindAddr, int aPort, int aLoginTimeout, int aResponseTimeout)
        throws SQLException
    {
        if (!isClosed())
        {
            Error.throwSQLException(ErrorDef.OPENED_CONNECTION);
        }

        mResponseTimeout = aResponseTimeout;
        
        if (isHostname(aAddr))
        {
            this.openWithName(aAddr, aBindAddr, aPort, aLoginTimeout, aResponseTimeout);
        }
        else
        {
            open(new InetSocketAddress(aAddr, aPort), aBindAddr, aLoginTimeout, aResponseTimeout);
        }
    }

    private void openWithName(String aHostname, String aBindAddr, int aPort, int aLoginTimeout, int aResponseTimeout)
        throws SQLException
    {
        List<InetAddress> sIpAddrList = getAllIpAddressesByName(aHostname, isPreferIPv6());

        int sIdx;
        InetAddress sAddr;
        InetSocketAddress sSockAddr;
        
        // try to connect using All IPs
        for (sIdx = 0; sIdx < sIpAddrList.size();)
        {
            sAddr = sIpAddrList.get(sIdx);
            sSockAddr = new InetSocketAddress(sAddr, aPort);
            if (TraceFlag.TRACE_COMPILE && TraceFlag.TRACE_ENABLED)
            {
                mCmLogger.log(Level.INFO, "openWithName: try to connect to " + sSockAddr);
            }
            try
            {
                open(sSockAddr, aBindAddr, aLoginTimeout, aResponseTimeout);
                break;
            }
            catch (SQLException sEx)
            {
                if (TraceFlag.TRACE_COMPILE && TraceFlag.TRACE_ENABLED)
                {
                    mCmLogger.log(Level.SEVERE, "Connection Failed by Hostname: " + sEx.getMessage());
                }
                sIdx++;
                // If all of the attempting failed, throw a exception.
                if (sIdx == sIpAddrList.size())
                {
                    throw sEx;
                }
            }
        }
    }

    /**
     * 물리 연결을 맺고, CmChannel을 사용하기 위해 준비를 한다.
     * 
     * @param aSockAddr        접속할 서버
     * @param aBindAddr        소켓에 바인드 할 IP 주소. null이면 바인드 하지 않음.
     * @param aLoginTimeout    Socket.connect()에 사용할 타임아웃 값 (초 단위)
     * @param aResponseTimeout 소켓 타임아웃 값 (초 단위)
     * 
     * @throws SQLException 이미 연결되어있거나 소켓 생성, 초기화 또는 연결에 실패한 경우
     */
    private void open(SocketAddress aSockAddr, String aBindAddr, int aLoginTimeout, int aResponseTimeout)
        throws SQLException
    {
        try
        {
            if (mUseSsl)
            {
                mSocket = getSslSocketFactory(mSslProps).createSocket();
                if (mSslProps.getCipherSuiteList() != null)
                {
                    ((SSLSocket)mSocket).setEnabledCipherSuites(mSslProps.getCipherSuiteList());
                }
            }
            else
            {
                mSocket = new Socket();
            }
    
            if (aBindAddr != null)
            {
                mSocket.bind(new InetSocketAddress(aBindAddr, 0));
            }
            if (aResponseTimeout > 0)
            {
                mSocket.setSoTimeout(aResponseTimeout * 1000);
            }
            mSocket.setKeepAlive(true);
            mSocket.setReceiveBufferSize(mSockRcvBufSize); // PROJ-2625
            mSocket.setSendBufferSize(CM_DEFAULT_SNDBUF_SIZE);

            mSocket.connect(aSockAddr, aLoginTimeout * 1000);
        
            if (mUseSsl)
            {
                ((SSLSocket)mSocket).setEnabledProtocols(new String[] { "TLSv1" });
                ((SSLSocket)mSocket).startHandshake();
            }
    
            mWriteChannel = Channels.newChannel(mSocket.getOutputStream());
            mReadChannel = Channels.newChannel(mSocket.getInputStream());
            initToWrite();
        }
        catch (SQLException sEx)
        {
            quiteClose();
            throw sEx;
        }
        // connect 실패시 날 수 있는 예외가 한종류가 아니므로 모든 예외를 잡아 연결 실패로 처리한다.
        // 예를들어, AIX 6.1에서는 ClosedSelectorException가 나는데 이는 RuntimeException이다. (ref. BUG-33341)
        catch (Exception sCauseEx)
        {
            quiteClose();
            Error.throwCommunicationErrorException(sCauseEx);
        }
    }

    /**
     * 인증서 정보를 이용해 SSLSocketFactory 객체를 생성한다.
     * @param aCertiProps ssl관련 설정 정보를 가지고 있는 객체
     * @return SSLSocketFactory 객체
     * @throws SQLException keystore에서 에러가 발생한 경우
     */
    private SSLSocketFactory getSslSocketFactory(SSLProperties aCertiProps) throws SQLException
    {
        String sKeyStoreUrl = aCertiProps.getKeyStoreUrl();
        String sKeyStorePassword = aCertiProps.getKeyStorePassword();
        String sKeyStoreType = aCertiProps.getKeyStoreType();
        String sTrustStoreUrl = aCertiProps.getTrustStoreUrl();
        String sTrustStorePassword = aCertiProps.getTrustStorePassword();
        String sTrustStoreType = aCertiProps.getTrustStoreType();

        TrustManagerFactory sTmf = null;
        KeyManagerFactory sKmf = null;

        try
        {
            sTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            sKmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        }
        catch (NoSuchAlgorithmException sNsae)
        {
            Error.throwSQLException(ErrorDef.DEFAULT_ALGORITHM_DEFINITION_INVALID, sNsae);
        }

        loadKeyStore(sKeyStoreUrl, sKeyStorePassword, sKeyStoreType, sKmf);

        // BUG-40165 verify_server_certificate가 true일때만 TrustManagerFactory를 초기화 해준다.
        if (aCertiProps.verifyServerCertificate())
        {
            loadKeyStore(sTrustStoreUrl, sTrustStorePassword, sTrustStoreType, sTmf);
        }
        
        return createAndInitSslContext(aCertiProps, sKeyStoreUrl, sTrustStoreUrl, sTmf, sKmf).getSocketFactory();
    }

    private SSLContext createAndInitSslContext(SSLProperties aCertiInfo, String aKeyStoreUrl, String aTrustStoreUrl,
                                               TrustManagerFactory aTmf, KeyManagerFactory aKmf) throws SQLException
    {
        SSLContext sSslContext = null;

        try 
        {
            sSslContext = SSLContext.getInstance("TLS");
            
            KeyManager[] sKeyManagers = (StringUtils.isEmpty(aKeyStoreUrl)) ?  null : aKmf.getKeyManagers();
            TrustManager[] sTrustManagers;
            if (aCertiInfo.verifyServerCertificate())
            {
                sTrustManagers = (StringUtils.isEmpty(aTrustStoreUrl)) ? null : aTmf.getTrustManagers();
            }
            else
            {
                sTrustManagers = new X509TrustManager[] { BlindTrustManager.getInstance() };
            }
            sSslContext.init(sKeyManagers, sTrustManagers, null);
            
        }
        catch (NoSuchAlgorithmException sNsae) 
        {
            Error.throwSQLException(ErrorDef.UNSUPPORTED_KEYSTORE_ALGORITHM, sNsae.getMessage(), sNsae);
        } 
        catch (KeyManagementException sKme) 
        {
            Error.throwSQLException(ErrorDef.KEY_MANAGEMENT_EXCEPTION_OCCURRED, sKme.getMessage(), sKme);
        }
        return sSslContext;
    }

    /**
     * PROJ-2474 KeyStore 및 TrustStore 파일을 읽어들인다.
     */
    private void loadKeyStore(String aKeyStoreUrl, String aKeyStorePassword, String aKeyStoreType, 
                                    Object aKeystoreFactory) throws SQLException
    {
        if (StringUtils.isEmpty(aKeyStoreUrl)) return;
        InputStream keyStoreStream = null;
        
        try 
        {
            aKeyStoreType = (StringUtils.isEmpty(aKeyStoreType)) ? DEFAULT_KEYSTORE_TYPE : aKeyStoreType;
            KeyStore sClientKeyStore = KeyStore.getInstance(aKeyStoreType);
            URL sKsURL = new URL(DEFAULT_URL_FILE_SCHEME + aKeyStoreUrl);
            char[] sPassword = (aKeyStorePassword == null) ? new char[0] : aKeyStorePassword.toCharArray();
            keyStoreStream = sKsURL.openStream();
            sClientKeyStore.load(keyStoreStream, sPassword);
            keyStoreStream.close();
            if (aKeystoreFactory instanceof KeyManagerFactory)
            {
                ((KeyManagerFactory)aKeystoreFactory).init(sClientKeyStore, sPassword);
            }
            else if (aKeystoreFactory instanceof TrustManagerFactory)
            {
                ((TrustManagerFactory)aKeystoreFactory).init(sClientKeyStore);
            }
        }
        catch (UnrecoverableKeyException sUke) 
        {
            Error.throwSQLException(ErrorDef.CAN_NOT_RETREIVE_KEY_FROM_KEYSTORE, sUke);
        } 
        catch (NoSuchAlgorithmException sNsae) 
        {
            Error.throwSQLException(ErrorDef.UNSUPPORTED_KEYSTORE_ALGORITHM, sNsae.getMessage(), sNsae);   
        } 
        catch (KeyStoreException sKse) 
        {
            Error.throwSQLException(ErrorDef.CAN_NOT_CREATE_KEYSTORE_INSTANCE, sKse.getMessage(), sKse);
        } 
        catch (CertificateException sNsae) 
        {
            Error.throwSQLException(ErrorDef.CAN_NOT_LOAD_KEYSTORE, aKeyStoreType, sNsae);
        } 
        catch (MalformedURLException sMue) 
        {
            Error.throwSQLException(ErrorDef.INVALID_KEYSTORE_URL, sMue);   
        } 
        catch (IOException sIoe) 
        {
            Error.throwSQLException(ErrorDef.CAN_NOT_OPEN_KEYSTORE, sIoe);
        }
        finally
        {
            try
            {
                if (keyStoreStream != null)
                {
                    keyStoreStream.close();
                }
            }
            catch (IOException sIOe)
            {
                Error.throwSQLExceptionForIOException(sIOe.getCause());
            }
        }
    }
    
    public boolean isClosed()
    {
        return (mState == CHANNEL_STATE_CLOSED);
    }

    public void close() throws IOException
    {
        if (mState != CHANNEL_STATE_CLOSED)
        {
            if (mWriteChannel != null)
            {
                mWriteChannel.close();
                mWriteChannel = null;
            }
            if (mReadChannel != null)
            {
                mReadChannel.close();
                mReadChannel = null;
            }
            if (mSocket != null)
            {
                mSocket.close();
                mSocket = null;
            }
            mSocketFD = INVALID_SOCKTFD;
            mState = CHANNEL_STATE_CLOSED;
        }
    }

    /**
     * 채널을 닫는다.
     * 이 때, 예외가 발생하더라도 조용히 넘어간다.
     */
    public void quiteClose()
    {
        try
        {
            close();
        }
        catch (IOException ex)
        {
            // quite
        }
    }

    public void setCharset(Charset aCharset, Charset aNCharset)
    {
        super.setCharset(aCharset, aNCharset); // BUG-45156 encoder에 대한 부분은 CmBufferWrite에서 처리

        mDBDecoder = aCharset.newDecoder();
        mNCharDecoder = aNCharset.newDecoder();

        mDBDecoder.onMalformedInput(CodingErrorAction.REPORT);
        mDBDecoder.onUnmappableCharacter(CodingErrorAction.REPORT);
        mNCharDecoder.onMalformedInput(CodingErrorAction.REPORT);
        mNCharDecoder.onUnmappableCharacter(CodingErrorAction.REPORT);

        CLOB_BUF_SIZE = LobConst.LOB_BUFFER_SIZE  / getByteLengthPerChar();

        // PROJ-2427 getBytes시 사용할 Encoder를 ColumnFactory에 셋팅
        mColumnFactory.setCharSetEncoder(mDBEncoder);
        mColumnFactory.setNCharSetEncoder(mNCharEncoder);
    }
    
    public Charset getCharset()
    {
        return mDBEncoder.charset();
    }
    
    public Charset getNCharset()
    {
        return mNCharEncoder.charset();
    }
    
    public int getByteLengthPerChar()
    {
        return (int)Math.ceil(mDBEncoder.maxBytesPerChar());
    }
    
    void writeOp(byte aOpCode) throws SQLException
    {
        checkWritable(1);
        mBuffer.put(aOpCode);
    }

    public void writeBytes(byte[] aValue, int aOffset, int aLength) throws SQLException
    {
        checkWritable(aLength);
        mBuffer.put(aValue, aOffset, aLength);
    }
    
    int writeBytes4Clob(ReadableCharChannel aChannelFromSource, long aLocatorId, boolean isCopyMode) throws SQLException
    {
        throwErrorForClosed();

        int sByteReadSize = -1;
        try
        {
            checkWritable4Lob(LobConst.LOB_BUFFER_SIZE);

            int sOrgPos4Op = mBuffer.position();
            mBuffer.position(sOrgPos4Op + LobConst.FIXED_LOB_OP_LENGTH_FOR_PUTLOB);
            sByteReadSize = aChannelFromSource.read(mBuffer);
            if (sByteReadSize == -1)
            {
                mBuffer.position(sOrgPos4Op);
            }
            else
            {
                mBuffer.position(sOrgPos4Op);
                this.writeOp(CmOperationDef.DB_OP_LOB_PUT);
                this.writeLong(aLocatorId);
                this.writeUnsignedInt(sByteReadSize);
                mBuffer.position(mBuffer.position() + sByteReadSize);

                if (isCopyMode)
                {
                    sendPacket(false);
                    initToWrite();

                    Object sSrc = aChannelFromSource.getSource();
                    if (sSrc instanceof ClobReader)
                    {
                        ((ClobReader)sSrc).readyToCopy();
                    }
                }
            }
        }
        catch (IOException sException)
        {
            Error.throwSQLExceptionForIOException(sException.getCause());
        }
        return sByteReadSize;
    }

    public void writeBytes(ByteBuffer aValue) throws SQLException
    {
        throwErrorForClosed();

        while (aValue.remaining() > 0)
        {
            if (aValue.remaining() <= mBuffer.remaining())
            {
                mBuffer.put(aValue);
            }
            else
            {
                int sOrgLimit = aValue.limit();
                aValue.limit(aValue.position() + mBuffer.remaining());
                mBuffer.put(aValue);
                sendPacket(false); // 분할 됨 = 마지막이 아님

                aValue.limit(sOrgLimit);
                initToWrite();
            }
        }
    }

    public void sendAndReceive() throws SQLException
    {
        throwErrorForClosed();
        sendPacket(true);
        initToRead();
        receivePacket();
    }
    
    public void send() throws SQLException
    {
        throwErrorForClosed();
        sendPacket(true);
        initToWrite();
    }

    public void receive() throws SQLException
    {
        throwErrorForClosed();
        initToRead();
        receivePacket();
    }

    /**
     * LOB I/O에 사용할 임시 버퍼를 얻는다.
     * <p>
     * 동시성 문제가 발생할 수 있으므로 반드시 synchronozed 구간에서만 사용해야한다.
     * 
     * @return LOB I/O에 사용할 임시 버퍼
     */
    byte[] getLobBuffer()
    {
        if (mLobBuf == null)
        {
            mLobBuf = new byte[LobConst.LOB_BUFFER_SIZE];
        }
        return mLobBuf;
    }
    
    int checkWritable4Lob(long aLengthToWrite) throws SQLException
    {
        checkWritable(LobConst.FIXED_LOB_OP_LENGTH_FOR_PUTLOB);

        if (mBuffer.remaining() < (aLengthToWrite + LobConst.FIXED_LOB_OP_LENGTH_FOR_PUTLOB))
        {
            return mBuffer.remaining() - LobConst.FIXED_LOB_OP_LENGTH_FOR_PUTLOB; 
        }
        else
        {
            return (int)aLengthToWrite;
        }
    }
    
    public void checkWritable(int aNeedToWrite) throws SQLException
    {
        throwErrorForClosed();

        if (mState == CHANNEL_STATE_READABLE)
        {
            initToWrite();
        }
        else
        {
            if (mBuffer.remaining() < aNeedToWrite)
            {
                sendPacket(false);
                initToWrite();
            }
        }
    }

    private void writePacketHeader(int aPayloadLength, boolean aIsLast)
    {
        mBuffer.put(CM_PACKET_HEADER_VERSION);
        mBuffer.put(CM_PACKET_HEADER_RESERVED_BYTE);
        mBuffer.putShort((short) aPayloadLength);
        
        if(aIsLast) 
        {
            mBuffer.putInt(setLast(mNextSendSeqNo));
        }
        else
        {
            mBuffer.putInt(mNextSendSeqNo);
        }
        
        mBuffer.putShort(CM_PACKET_HEADER_RESERVED_SHORT);
        mBuffer.putShort(mSessionID);
        mBuffer.putInt(CM_PACKET_HEADER_RESERVED_INT);
        
        mNextSendSeqNo = incAsUnsignedInt(mNextSendSeqNo);
    }

    private void initToWrite()
    {
        mBuffer.clear();
        mBuffer.position(CM_PACKET_HEADER_SIZE);
        mState = CHANNEL_STATE_WRITABLE;
    }

    private void sendPacket(boolean aIsLast) throws SQLException
    {
        int sTotalWrittenLength = mBuffer.position();
        mBuffer.flip();
        writePacketHeader(sTotalWrittenLength - CM_PACKET_HEADER_SIZE, aIsLast);
        mBuffer.rewind();
        try
        {
            // PROJ-2583 jdbc packet logging
            if (TraceFlag.TRACE_COMPILE && TraceFlag.TRACE_ENABLED && mCmLogger.isLoggable(Level.FINEST))
            {
                byte[] sByteArry = new byte[mBuffer.remaining()];
                mBuffer.get(sByteArry);
                mBuffer.rewind();
                String sDumpedBytes = DumpByteUtil.dumpBytes(sByteArry, 0, sByteArry.length);
                mCmLogger.log(Level.FINEST, sDumpedBytes, Integer.toHexString(mSessionID & 0xffff));
            }
            while (mBuffer.hasRemaining())
            {
                mWriteChannel.write(mBuffer);
            }
        }
        catch (IOException sException)
        {
            Error.throwCommunicationErrorException(sException);
        }        
    }

    private void initToRead()
    {
        // 비동기로 요청한 상태에서 동기로 송수신 하고자 한다면, 하나의 CM block 내에 둘 이상의 packet 이 들어 있을 수 있음.
        // 이 경우, mPendedDataLength 값이 0 이 아니기 때문에 무조건 mBuffer.clear() 하지 않도록 하고,
        // 이후 receivePacket() 함수에서 mBuffer 를 조정하고 덜 수신한 packet data 를 마저 수신함. 
        if (mPendedDataLength == 0)
        {
            mBuffer.clear();
        }
        mState = CHANNEL_STATE_READABLE;
    }

    /**
     * 데이타를 aSkipLen 만큼 건너 뛴다.
     * <p>
     * 만약, aSkipLen이 0이면 조용히 무시한다.
     *
     * @param aSkipLen 건너 뛸 byte 수
     * @throws InternalError aSkipLen이 0보다 작을 경우
     */
    public void skip(int aSkipLen)
    {
        if (aSkipLen < 0)
        {
            Error.throwInternalError(ErrorDef.INVALID_ARGUMENT,
                                     "Skip length",
                                     "0 ~ Integer.MAX_VALUE",
                                     String.valueOf(aSkipLen));
        }

        if (aSkipLen > 0)
        {
            mBuffer.position(mBuffer.position() + aSkipLen);
        }
    }

    private int initPendedData()
    {
        int pend = mPendedDataLength;
        if(mPendedDataLength > 0)
        {
            /* move for compact it, keep position */
            mBuffer.limit(mBuffer.limit() + mPendedDataLength);
            mBuffer.compact();
        }
        else
        {
            mBuffer.clear();
        }
        mPendedDataLength = 0;
        return pend;
    }
    
    private void readyToRead()
    {
        // Expected Size
        int size = mPayloadSize + CM_PACKET_HEADER_SIZE;
        
        // The exceeded amount of data more than expected data size
        mPendedDataLength = mBuffer.limit() - size;

        // BUGBUG Data loss occured
        if(mPendedDataLength < 0)
        {
            mPendedDataLength = 0;
        }    
        
        // Skip Header Size
        mBuffer.position(CM_PACKET_HEADER_SIZE);
        
        // To definite limitation to read one packet exactly 
        mBuffer.limit(size);
    }
    
    private void receivePacket() throws SQLException
    {
        long sLeftPacket, sReadLength;

        /* 1.0 Read At Least Header, include pending */
        for (sLeftPacket = CM_PACKET_HEADER_SIZE - initPendedData(); sLeftPacket > 0; sLeftPacket -= sReadLength)
        {
            throwErrorForThreadInterrupted();
            sReadLength = readFromSocket();
        }

        /*
         * 2.0 gets a left, left could be <0 - it mean that read more then
         * header bytes, so do not read it.
         */
        for (sLeftPacket += parseHeader(); sLeftPacket > 0; sLeftPacket -= sReadLength)
        {
            throwErrorForThreadInterrupted();
            // bb.limit(bb.capacity()); - this is Ok too but a lot of panding
            // bytes
            mBuffer.limit(CM_PACKET_HEADER_SIZE + mPayloadSize);
            sReadLength = readFromSocket();
        }
        
        // PROJ-2583 jdbc packet logging
        if (TraceFlag.TRACE_COMPILE && TraceFlag.TRACE_ENABLED && mCmLogger.isLoggable(Level.FINEST))
        {
            mBuffer.rewind();  // BUG-43694 limit를 하지 않고 바로 rewind한다.
            byte[] sByteArry = new byte[mBuffer.remaining()];
            mBuffer.get(sByteArry);
            String sDumpedBytes = DumpByteUtil.dumpBytes(sByteArry, 0, sByteArry.length);
            mCmLogger.log(Level.FINEST, sDumpedBytes, String.valueOf(mSessionID));
        }
        
        readyToRead();
    }

    private int readFromSocket() throws SQLException
    {
        int sRead = 0;
        
        while (true)
        {
            try
            {
                sRead = mReadChannel.read(mBuffer);
            }
            catch (SocketTimeoutException sTimeoutException)
            {
                quiteClose(); // RESPONSE_TIMEOUT이 나면 연결을 끊는다.
                Error.throwSQLException(ErrorDef.RESPONSE_TIMEOUT, String.valueOf(mResponseTimeout), sTimeoutException);
            }
            catch (IOException sException)
            {
                Error.throwCommunicationErrorException(sException);
            }
            
            if (sRead == -1)
            {
                Error.throwSQLException(ErrorDef.COMMUNICATION_ERROR, "There was no response from the server, and the channel has reached end-of-stream.");
            }

            if (sRead > 0)
            {
                break;
            }
        }
        
        return sRead;
    }
    
    byte readOp() throws SQLException
    {
        if (mBuffer.remaining() == 0)
        {
            if (mIsLastPacket)
            {
                return NO_OP;
            }
            receivePacket();
        }
        return mBuffer.get();
    }

    public byte readByte() throws SQLException
    {
        checkReadable(1);
        readyToReadAfterCheck();
        return mBuffer.get();
    }

    public short readUnsignedByte() throws SQLException
    {
        checkReadable(1);
        readyToReadAfterCheck();
        byte sByte = mBuffer.get();
        return (short) (sByte & 0xFF);
    }

    public short readShort() throws SQLException
    {
        checkReadable(2);
        readyToReadAfterCheck();
        return mBuffer.getShort();
    }

    public int readUnsignedShort() throws SQLException
    {
        checkReadable(2);
        readyToReadAfterCheck();
        short sShort = mBuffer.getShort();
        return sShort & 0xFFFF;
    }
    
    private void readyToReadAfterCheck()
    {
        mBuffer.limit(CM_PACKET_HEADER_SIZE + mPayloadSize);
    }

    public int readInt() throws SQLException
    {
        checkReadable(4);
        readyToReadAfterCheck();
        return mBuffer.getInt();
    }
    
    long readUnsignedInt() throws SQLException
    {
        checkReadable(4);        
        readyToReadAfterCheck();
        return mBuffer.getInt() & 0xFFFFFFFFL;
    }

    public BigInteger readUnsignedLong() throws SQLException
    {
        checkReadable(8);
        readyToReadAfterCheck();
        byte[] uLongMagnitude = new byte[8];
        mBuffer.get(uLongMagnitude);
        return new BigInteger(1, uLongMagnitude);
    }
    
    public long readLong() throws SQLException
    {
        checkReadable(8);
        readyToReadAfterCheck();
        return mBuffer.getLong();
    }

    public float readFloat() throws SQLException
    {
        checkReadable(4);
        readyToReadAfterCheck();
        return mBuffer.getFloat();
    }

    public double readDouble() throws SQLException
    {
        checkReadable(8);
        readyToReadAfterCheck();
        return mBuffer.getDouble();
    }

    public void readBytes(byte[] aDest) throws SQLException
    {
        readBytes(aDest, aDest.length);
    }
    
    public void readBytes(byte[] aDest, int aOffset, int aLength) throws SQLException
    {
        for (int sBufPos = aOffset, sNeededBytesSize = aLength;;)
        {
            int sReadableBytesSize = mBuffer.remaining();
            if (sNeededBytesSize <= sReadableBytesSize)
            {
                int sOrgLimit = mBuffer.limit();
                mBuffer.limit(mBuffer.position() + sNeededBytesSize);
                mBuffer.get(aDest, sBufPos, sNeededBytesSize);
                mBuffer.limit(sOrgLimit);
                throwErrorForPacketTwisted(sBufPos + sNeededBytesSize - aOffset != aLength);
                break;
            }
            else /* if (sNeededBytesSize > mBuf.remaining()) */
            {
                mBuffer.get(aDest, sBufPos, sReadableBytesSize);
                sBufPos += sReadableBytesSize;
                sNeededBytesSize -= sReadableBytesSize;
                receivePacket();
            }
        }
    }
    
    public void readBytes(byte[] aDest, int aLength) throws SQLException
    {
        readBytes(aDest, 0, aLength);
    }

    public void readBytes(ByteBuffer aBuf) throws SQLException
    {
        for (int sNeededBytesSize = aBuf.remaining();;)
        {
            int sReadableBytesSize = mBuffer.remaining();
            if (sNeededBytesSize <= sReadableBytesSize)
            {
                int sOrgLimit = mBuffer.limit();
                mBuffer.limit(mBuffer.position() + sNeededBytesSize);
                aBuf.put(mBuffer);
                mBuffer.limit(sOrgLimit);
                throwErrorForPacketTwisted(aBuf.remaining() > 0);
                break;
            }
            else /* if (sNeededBytesSize > mBuf.remaining()) */
            {
                aBuf.put(mBuffer);
                sNeededBytesSize -= sReadableBytesSize;
                receivePacket();
            }
        }
    }

    String readString(int aByteSize, int aSkipLength) throws SQLException
    {
        StringBuffer sStrBuf = readStringBy(aByteSize, aSkipLength, mDBDecoder);
        return sStrBuf.toString();
    }
    
    void readCharArrayTo(char[] aDst, int aDstOffset, int aByteLength, int aCharLength) throws SQLException
    {
        StringBuffer sStrBuf = readStringBy(aByteLength, 0, mDBDecoder);
        sStrBuf.getChars(0, aCharLength, aDst, aDstOffset);
    }
    
    public String readString(byte[] aBytesValue)
    {
        return decodeString(ByteBuffer.wrap(aBytesValue), mDBDecoder);
    }

    /**
     * Char, Varchar 컬럼 데이터를 읽은 후 Charset에 맞게 decode한 문자열을 리턴한다.
     * @param aColumnSize 컬럼데이터 사이즈
     * @param aSkipSize  스킵사이즈
     * @param aIsNationalCharset 다국어캐릭터셋인지 여부
     * @return 디코딩된 char, varchar컬럼 스트링
     * @throws SQLException 정상적으로 컬럼데이터를 받아오지 못한 경우
     */
    public String readCharVarcharColumnString(int aColumnSize, int aSkipSize,
                                              boolean aIsNationalCharset) throws SQLException
    {
        mCharVarcharColumnBuffer.clear();
        mCharVarcharColumnBuffer.limit(aColumnSize);
        this.readBytes(mCharVarcharColumnBuffer);
        this.skip(aSkipSize);
        mCharVarcharColumnBuffer.flip();

        return  aIsNationalCharset ? decodeString(mCharVarcharColumnBuffer, mNCharDecoder) :
                                     decodeString(mCharVarcharColumnBuffer, mDBDecoder);
    }

    private String decodeString(ByteBuffer aByteBuf, CharsetDecoder aDecoder)
    {
        mCharBuf.clear();
        while (aByteBuf.remaining() > 0)
        {
            CoderResult sResult = aDecoder.decode(aByteBuf, mCharBuf, true);
            if (sResult == CoderResult.OVERFLOW)
            {
                Error.throwInternalError(ErrorDef.INTERNAL_BUFFER_OVERFLOW);
            }
            if (sResult.isError())
            {
                char data = (char)aByteBuf.get();
                if (sResult.isUnmappable())
                {
                    // fix BUG-27782 변환할 수 없는 문자는 '?'로 출력
                    mCharBuf.put('?');
                }
                else if (sResult.isMalformed())
                {
                    mCharBuf.put(data);
                }
            }
        }
        mCharBuf.flip();
        return mCharBuf.toString();
    }

    String readStringForErrorResult() throws SQLException
    {
        int sSize = readUnsignedShort();
        byte[] sBuf = new byte[sSize];
        readBytes(sBuf);
        return new String(sBuf);
    }

    String readStringForMessage() throws SQLException
    {
        long sSize = readUnsignedInt();
        byte[] sBuf = new byte[(int)sSize];
        readBytes(sBuf);

        long sRestLength = sSize - Integer.MAX_VALUE;
        while (sRestLength > 0)
        {
            if (sRestLength >= Integer.MAX_VALUE)
            {
                skip(Integer.MAX_VALUE);
                sRestLength -= Integer.MAX_VALUE;
            }
            else
            {
                skip((int)sRestLength);
                sRestLength = 0;
            }
        }

        return new String(sBuf);
    }

    String readStringForColumnInfo() throws SQLException
    {
        int sStrLen = (int)readByte();
        if (sStrLen == 0)
        {
            return null;
        }

        byte[] sBuf = new byte[sStrLen];
        readBytes(sBuf);
        return new String(sBuf);
    }
    
    private void checkReadable(int aLengthToRead) throws SQLException
    {
        if (mState != CHANNEL_STATE_READABLE)
        {
            Error.throwInternalError(ErrorDef.INVALID_STATE, "CHANNEL_STATE_READABLE", CHANNEL_STATE_STRING[mState]);
        }

        if (mBuffer.remaining() < aLengthToRead)
        {
            if (mIsLastPacket) 
            {
                Error.throwInternalError(ErrorDef.MORE_DATA_NEEDED);
            }

            if (mPrimitiveTypeBuf == null)
            {
                mPrimitiveTypeBuf = ByteBuffer.allocateDirect(MAX_PRIMITIVE_TYPE_SIZE);
            }

            int sRemain = mBuffer.remaining();

            if (sRemain > MAX_PRIMITIVE_TYPE_SIZE)
            {
                Error.throwInternalError(ErrorDef.EXCEED_PRIMITIVE_DATA_SIZE);
            }

            if (sRemain > 0)
            {
                mPrimitiveTypeBuf.clear();
                mPrimitiveTypeBuf.put(mBuffer);
                mPrimitiveTypeBuf.flip();
            }

            receivePacket();

            if (sRemain > 0)
            {
                mBuffer.position(mBuffer.position() - sRemain);
                int sPos = mBuffer.position();
                mBuffer.put(mPrimitiveTypeBuf);
                mBuffer.position(sPos);
            }
        }
    }

    private int parseHeader() throws SQLException
    {
        int sOrgPos = mBuffer.position();
        mBuffer.flip();

        byte sByte = mBuffer.get();
        if (sByte != CM_PACKET_HEADER_VERSION)
        {
            Error.throwInternalError(ErrorDef.INVALID_PACKET_HEADER_VERSION,
                                     String.valueOf(CM_PACKET_HEADER_VERSION),
                                     String.valueOf(sByte));
        }

        mBuffer.get();  // Reserved 1
        mPayloadSize = mBuffer.getShort();        
        int sSequenceNo = mBuffer.getInt();               
        mBuffer.getShort(); // Reserved 2

        mIsLastPacket = !isFragmented(sSequenceNo);

        mSessionID = mBuffer.getShort();
        mBuffer.getInt(); // Reserved 3
        
        mBuffer.position(sOrgPos);
        
        checkValidSeqNo(removeIntegerMSB(sSequenceNo));
        
        return mPayloadSize;
    }

    private StringBuffer readStringBy(int aByteSize, int aSkipLength, CharsetDecoder aDecoder) throws SQLException
    {
        StringBuffer sStrBuf = new StringBuffer();
        int sNeededBytesSize = aByteSize;
        int sReadableBytesSize;
        while (sNeededBytesSize > (sReadableBytesSize = mBuffer.remaining()))
        {
            mCharBuf.clear();
            CoderResult sCoderResult = aDecoder.decode(mBuffer, mCharBuf, true);
            if (sCoderResult == CoderResult.OVERFLOW)
            {
                Error.throwInternalError(ErrorDef.INTERNAL_BUFFER_OVERFLOW);
            }
            mCharBuf.flip();
            sStrBuf.append(mCharBuf.array(), mCharBuf.arrayOffset(), mCharBuf.limit());
            if (sCoderResult.isError()) // BUG-27782
            {
                char data = (char)mBuffer.get();
                if (sCoderResult.isUnmappable())
                {
                    sStrBuf.append("?");
                }
                else if (sCoderResult.isMalformed())
                {
                    sStrBuf.append(data);
                }
            }

            // 문자가 짤려 encode를 못해 버퍼에 남겨둔 데이타가 있을 수 있으므로
            // 남은 길이를 구해 다시 처리할 길이에 더해줘야 한다.
            int sRemainBytesSize = mBuffer.remaining();
            sNeededBytesSize -= sReadableBytesSize;
            checkReadable(sNeededBytesSize);
            sNeededBytesSize += sRemainBytesSize;
        }
        if (sNeededBytesSize > 0)
        {
            int sOrgLimit = mBuffer.limit();
            mBuffer.limit(mBuffer.position() + sNeededBytesSize);
            CoderResult sCoderResult;
            do
            {
                mCharBuf.clear();
                sCoderResult = aDecoder.decode(mBuffer, mCharBuf, true);
                mCharBuf.flip();
                sStrBuf.append(mCharBuf.array(), mCharBuf.arrayOffset(), mCharBuf.limit());
                if (sCoderResult.isError())
                {
                    // 해당 byte 들에 대해 mapping 할 수 있는 char 가 없을 경우 :
                    // ByteBuffer 를 소비 시켜주어야 다음 글자에 대한 처리가 가능하므로,
                    // 일단 get으로 데이터를 받은 뒤, 이를 강제로 string buffer에 넣음.
                    // 실제 encoding table 값과 다를 것이므로 깨진 문자로 표현됨.
                    // ('?' 로 표현되며, 코드값은 63)
                    char data = (char)mBuffer.get();
                    if (sCoderResult.isUnmappable())
                    {
                        // fix BUG-27782 변환할 수 없는 문자는 '?'로 출력한다.
                        sStrBuf.append("?");
                    }
                    else if (sCoderResult.isMalformed())
                    {
                        sStrBuf.append(data);
                    }
                }
            } while (sCoderResult != CoderResult.OVERFLOW && sCoderResult != CoderResult.UNDERFLOW);

            if (sCoderResult == CoderResult.OVERFLOW) 
            {
                Error.throwInternalError(ErrorDef.INTERNAL_BUFFER_OVERFLOW);
            }
            aSkipLength += mBuffer.remaining();
            mBuffer.limit(sOrgLimit);
            if (aSkipLength > 0)
            {
                mBuffer.position(mBuffer.position() + aSkipLength);
            }
        }
        return sStrBuf;
    }

    private void checkValidSeqNo(int aNumber) 
    {
        if (mPrevReceivedSeqNo + 1 == aNumber)
        {
            mPrevReceivedSeqNo = aNumber;
        }
        else
        {
            Error.throwInternalError(ErrorDef.INVALID_PACKET_SEQUENCE_NUMBER, String.valueOf(mPrevReceivedSeqNo + 1), String.valueOf(aNumber));
        }
    }
    
    private static int incAsUnsignedInt(int aNumber)
    {
        if (aNumber == 0x7FFFFFFF)
        {
            return 0;
        }

        return ++aNumber;
    }
    
    private static int removeIntegerMSB(int aNumber) 
    {
        return aNumber & 0x7FFFFFFF;
    }
    
    private static boolean isFragmented(int aSequenceNo)
    {
        return (aSequenceNo & 0x80000000) == 0;
    }

    private static int setLast(int aSequenceNo)
    {
        return aSequenceNo | 0x80000000;
    }

    public int getLobCacheThreshold()
    {
        return mLobCacheThreshold;
    }

    public void setLobCacheThreshold(int aLobCacheThreshold)
    {
        this.mLobCacheThreshold = aLobCacheThreshold;
    }

    ReadableCharChannel getReadChannel4Clob(Reader aSource) throws IOException
    {
        if(mReadChannel4Clob == null)
        {
            mReadChannel4Clob = new AltibaseReadableCharChannel();
        }
        
        mReadChannel4Clob.open(aSource);
        return mReadChannel4Clob;
    }
    
    ReadableCharChannel getReadChannel4Clob(Reader aSource, int aSourceLength) throws IOException
    {
        if(mReadChannel4Clob == null)
        {
            mReadChannel4Clob = new AltibaseReadableCharChannel();
        }
        
        mReadChannel4Clob.open(aSource, aSourceLength);
        return mReadChannel4Clob;
    }

    ReadableCharChannel getReadChannel4Clob(char[] aSource, int aOffset, int aLength) throws IOException
    {
        if(mReadChannel4Clob == null)
        {
            mReadChannel4Clob = new AltibaseReadableCharChannel();
        }
        
        mReadChannel4Clob.open(aSource, aOffset, aLength);
        return mReadChannel4Clob;
    }

    public void setRemoveRedundantMode(boolean aMode, AltibaseProperties aProps)
    {
        this.mRemoveRedundantMode = aMode;

        mColumnFactory.setProperties(aProps);
    }
    
    public ColumnFactory getColumnFactory()
    {
        return mColumnFactory;
    }
    
    private void throwErrorForClosed() throws SQLException
    {
        if (isClosed())
        {
            Error.throwSQLException(ErrorDef.CLOSED_CONNECTION);
        }
    }
    
    private void throwErrorForThreadInterrupted() throws SQLException
    {
        if (Thread.interrupted())
        {
            Error.throwSQLException(ErrorDef.THREAD_INTERRUPTED);
        }
    }
    
    private void throwErrorForPacketTwisted(boolean aTest) throws SQLException
    {
        if (aTest)
        {
            Error.throwSQLException(ErrorDef.PACKET_TWISTED);
        }
    }

    /**
     * PROJ-2474 ssl_enable 이 활성화 되어 있는 경우 mUseSsl flag를 true로 셋팅해주고, 인증서 관련된 정보를 읽어와
     * mSslProps 객체에 저장한다.
     * @param aProps  AltibaseProperties객체
     */
    public void setSslProps(AltibaseProperties aProps)
    {
        this.mSslProps = new SSLProperties(aProps);
        this.mUseSsl = mSslProps.isSslEnabled();
    }

    /**
     * 현재 SSLSocket에서 가능한 CipherSuites 리스트를 돌려준다.
     * @return CipherSuite 리스트
     */
    public String[] getCipherSuitList()
    {
        return ((SSLSocket)mSocket).getEnabledCipherSuites();
    }

    /**
     * Socket receive buffer 크기를 반환한다.
     */
    public int getSockRcvBufSize()
    {
        return mSockRcvBufSize;
    }

    /**
     * Socket receive buffer 크기를 설정한다.
     */
    public void setSockRcvBufSize(int aSockRcvBufSize) throws IOException
    {
        int sSockRcvBufSize = aSockRcvBufSize;

        if (!isClosed())
        {
            mSocket.setReceiveBufferSize(sSockRcvBufSize);
        }

        mSockRcvBufSize = sSockRcvBufSize;
    }

    /**
     * Socket receive buffer 크기를 CM block 단위로 설정한다.
     */
    public void setSockRcvBufBlockRatio(int aSockRcvBufBlockRatio) throws IOException
    {
        int sSockRcvBufSize;
        
        if (aSockRcvBufBlockRatio <= 0)
        {
            sSockRcvBufSize = CM_DEFAULT_RCVBUF_SIZE;
        }
        else
        {
            sSockRcvBufSize = aSockRcvBufBlockRatio * CM_BLOCK_SIZE;
        }

        if (!isClosed())
        {
            mSocket.setReceiveBufferSize(sSockRcvBufSize);
        }

        mSockRcvBufSize = sSockRcvBufSize;
    }

    /**
     * 비동기적으로 fetch 프로토콜을 송신한 protocol context 를 저장한다.
     */
    public void setAsyncContext(CmProtocolContext aContext)
    {
        mAsyncContext = aContext;
    }

    /**
     * 비동기적으로 fetch 프로토콜을 송신한 protocol context 를 얻는다.
     */
    public boolean isAsyncSent()
    {
        return (mAsyncContext != null);
    }

    /**
     * 비동기적으로 fetch 프로토콜을 송신한 protocol context 를 얻는다.
     */
    public CmProtocolContext getAsyncContext()
    {
        return mAsyncContext;
    }

    /**
     * Socket 의 file descriptor 를 반환한다.
     */
    public int getSocketFD() throws SQLException
    {
        throwErrorForClosed();

        if (mSocketFD == INVALID_SOCKTFD)
        {
            try
            {
                mSocketFD = SocketUtils.getFileDescriptor(mSocket);
            }
            catch (Exception e)
            {
                Error.throwSQLException(ErrorDef.COMMUNICATION_ERROR, "Failed to get a file descriptor of the socket.", e);
            }
        }

        return mSocketFD;
    }
}
