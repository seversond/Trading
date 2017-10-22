/* 

fx3 - forex 3 - strategy for mean reverting forex pairs

David Severson

hardcoded to be parameterized

- strategy parameters
- currency paris(real and synthetic
- various mgmt functions, port # s etc

--- next step ideas ----

- pull chunks of code into other files
- setup a parameter approach

*/

// package fx3;

import com.ib.client.CommissionReport;
import com.ib.client.Contract;
import com.ib.client.ContractDetails;
import com.ib.client.EClientSocket;
import com.ib.client.EWrapper;
import com.ib.client.Execution;
import com.ib.client.ExecutionFilter;
import com.ib.client.Order;
import com.ib.client.OrderState;
import com.ib.client.UnderComp;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;

public class fx3 implements EWrapper {
        String progversion = "0.4.1b";
        EClientSocket m_s = new EClientSocket(this);
        /* can't do this because it isn't a thread just and object
        m_s.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread t, Throwable e) {
                System.out.println(t + " throws exception: " + e);
            }
        });
        */
        // properties
        String stratName;
        String stratType;
        String stratDescription;
        String stratEnviron;
        String stratPortStr;
        int stratPort;
        String logLevel;
        String logFile;
        String curRBase;
        String curRQuote;
        String curRConIDStr;
        int curRConID;
        String curBase;
        String curQuote;
        String curConIDStr;
        int curConID;
        String curOrderQtyStr;
        int curOrderQty;
        String curNormalBuyStr;
        boolean curNormalBuy;
        String cur2Base;
        String cur2Quote;
        String cur2ConIDStr;
        int cur2ConID;
        String cur2OrderQtyStr;
        int cur2OrderQty;
        String cur2NormalBuyStr;
        boolean cur2NormalBuy;
        String ParmInnerStr;
        double ParmInner;
        String ParmOutterStr;
        double ParmOutter;
        String ParmStopStr;
        double ParmStop;
        
        static final Logger logger = Logger.getLogger("fx3Log"); // setup logger
        FileHandler fhLogger; // fh for logger
        StartSocketServer socketserver;
        
        // state for order processing ... exiting, position, inflightOrder
        // and exittime, thestop
        volatile boolean position=false;
        volatile boolean position2=false;
        volatile boolean exiting=false;
        volatile boolean exiting2=false;
        volatile boolean fullExit=false;
        volatile boolean orderPlaced=false;//todo???
        volatile boolean inflightOrder=false;// order number for unconfirmed order
        volatile boolean inflightOrder2=false;
        volatile int inflightOrderID;
        volatile int inflightOrderID2;
        // following is used for complex decision table.   
        // a buy in a reference pair could be a sell in one of the syn pairs
        // ex. EUR.SEK = EUR.USD and USD.SEK   normal buy = true for both
        //               EUR.USD and SEK.USD   normal buy is false for SEK.USD
        // the following tables explains the approach
        //   ref pair        syn pair 1   syn pair 2
        // --------------------------------------------------------
        //   <NormalBuy>       T     F      T     F
        //     buy            buy   sell   buy   sell
        //     sell           sell  buy    sell  buy 
        // --------------------------------------------------------
        volatile boolean curEnterLong;
        volatile boolean cur2Enterlong;
        volatile double synPairStop;
        
        volatile int positionID=0;           // each time there is a position
            // may need to be persistant database.
        volatile int inflightOrders;         // number of unconfirmed orders
        volatile long exitTime;
        volatile long exitTime2;             // maybe not used
        volatile boolean longOrderR=true;
        volatile boolean longOrder=true;     // dirctn of any confirmed or unconf'd order
        volatile boolean longOrder2=true;
        volatile long entryTime;
        volatile long entryTime2;      // maybe not used
        volatile double entryPriceMkt; // price from market bars
        volatile double entryPrice;    // avg fill price
        volatile double entryPrice2;   // avg fill price
        volatile double exitPrice;     // avg fill price
        volatile double exitPrice2;    // avg fill price
        volatile String exitReason;
        volatile String exitReason2;   // todo mabe not needed
        volatile tradeBar lastbar;
        volatile tradeBar lastbar2;
        // end state
        
        int mode;   // paper, trading, draining, shutdown
        SimpleDateFormat secondsText = new SimpleDateFormat("ss");
        boolean connectionOpen=false;
        int count=0;
        double min, max;
        double ma, stdev;
        
        // start params
        long fixedExitTime=3600; // 1 hr = 3600 sec 
        Contract conR; // reference pair
        Contract con;  // first half of synthetic pair
        Contract con2; // second half of synthetic pair
        // end params
        
        ScheduledThreadPoolExecutor SchedExecutor; // for rerequest of mkt data
        volatile long lastMktBarTime;
        int nextOrderId; // will have to move to another/shared system in future
        int BIDTickerID = 100;
        int BIDTickerID2 = 101;
        int ASKTickerID = 200;
        int latetimer_default = 100;         // tunable parameter
        int latetimer=latetimer_default;
                
        class Trade
        {
            int     orderId;
            boolean longOrder;
        }
        
        class Bars
        {   
            public long bartime;
            public double barclose;
            public int reqId;
            public String bartype;
        }
        
        class tradeBar
        {
            long bartime;
            double AskClose;
            double BidClose;
            double BidClose2;
            boolean sellflag=false;
            boolean buyflag=false;
            long exittime;
        }

        List Trades = new ArrayList();
        // List rtbars = new ArrayList();
        List tradeBars = new ArrayList();

        private void checkDateTime() {
            Date date = new Date();
            Calendar calendar = GregorianCalendar.getInstance();
            calendar.setTime(date);
            calendar.get(Calendar.HOUR_OF_DAY);
            calendar.get(Calendar.DAY_OF_WEEK);            
        }
        
        private synchronized void reConnect() {
            m_s.eDisconnect();
            try {Thread.sleep(1000);} catch (InterruptedException e) {
                logger.log(Level.SEVERE,"trace: " + e);                          
            }
            logger.log(Level.INFO,"going reconnect, eConnect");
            m_s.eConnect("localhost", stratPort, 10);
            try {Thread.sleep(1000);} catch (InterruptedException e) {
                logger.log(Level.SEVERE,"trace2: " + e); }
        }   
  
        private synchronized void reqExecutions() {
            int reqID=901;
            ExecutionFilter filter = new ExecutionFilter();
            filter.m_exchange="IDEALPRO";
            m_s.reqExecutions(reqID, filter);
        }   
            
        private synchronized void RequestMktData() {
            //m_s.cancelRealTimeBars(BIDTickerID); --- for reference if needed
            logger.log(Level.INFO,"requesting mkt data");
            // get the ask for reference pair
            m_s.reqRealTimeBars(ASKTickerID, conR, 5, "ASK", false, null);
            // we want to watch BID pairs for exit stops
            m_s.reqRealTimeBars(BIDTickerID, con, 5, "BID", false, null);
            m_s.reqRealTimeBars(BIDTickerID2, con2, 5, "BID", false, null);
        }
        
        public synchronized void crashdump(String crashmsg) {
            // when the program has to stop
            // to add...
            // ... dump all variables for later review
            // send out a warning(SMS) that something has failed
            logger.log(Level.SEVERE,crashmsg);
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                logger.log(Level.SEVERE," exception on sleep ");
            }
            System.exit(-1);
        }
        
        public synchronized String getversion() {
            logger.log(Level.INFO,"running method");
            return progversion;
        }

        private synchronized void takePosition() {
            Order order1 = new Order();
            Order order2 = new Order();
            long threadId = Thread.currentThread().getId();
            boolean longPos = true; // need to setup state table
            positionID++;   // everytime we take a position we increment this
            // ---- prep security 1
            inflightOrder=true;
            inflightOrder2=true;
            longOrder=true;
            longOrder2=true;
            tradeBar bar = getLastBar();
            if (bar.buyflag) {
                if (!curNormalBuy) longOrder=false;
                if (!cur2NormalBuy) longOrder2=false;
                synPairStop=ma - stdev*ParmStop; // set stop below purchase
            } 
            if (bar.sellflag) {
                if (curNormalBuy) longOrder=false;
                if (cur2NormalBuy) longOrder2=false;
                synPairStop=ma + stdev*ParmStop; // set stop above purchase
            }
            if (longOrder) order1.m_action = "BUY";
            else order1.m_action = "SELL";
            if (longOrder2) order2.m_action = "BUY";
            else order2.m_action = "SELL";
            curEnterLong=longOrder; // save for the exit
            cur2Enterlong=longOrder2; // save for the exit
            // most of setup is ready... two parts now
            order1.m_totalQuantity = curOrderQty; // todo set cur qty
            order1.m_orderType = "MKT";
            entryTime= System.currentTimeMillis();
            entryPriceMkt=lastbar.AskClose; // todo check ask? ref or real
            inflightOrderID=nextOrderId;
            m_s.placeOrder(nextOrderId, con, order1);
            nextOrderId++;                       
            // ---- prep security 2
            order2.m_totalQuantity = curOrderQty; // todo set cur qty
            order2.m_orderType = "MKT";
            entryPriceMkt=lastbar.AskClose; // todo check ask? ref or real
            inflightOrderID2=nextOrderId;
            m_s.placeOrder(nextOrderId, con2, order2);
            nextOrderId++;                       
            logger.log(Level.INFO," take postion: " + position +
                " inflightOrder: " + inflightOrder + 
                " position2: " + position2 +
                " inflightOrder2: " + inflightOrder2 +
                " positionID: " + positionID +
                " synPairStop: " + synPairStop +
                " m_action: " + order1.m_action +
                " m_action(2): " + order2.m_action +
                " m_qty: " + order1.m_totalQuantity +
                " m_qty2: " + order2.m_totalQuantity +
                " exiting: " + exiting + " threadId: " + threadId);
            // todo need to persist order state in database?
        }
                
        private synchronized void exitPosition() {
            // todo ... change this to exit for both securitys
            // look to a buy vs sell indicator to be setup at the "take" time
            // place trade
            Order order = new Order();
            Order order2 = new Order();
            fullExit=true;
            inflightOrder=true;
            inflightOrder2=true;
            if (curEnterLong) order.m_action = "SELL";  
            else order.m_action = "BUY";
            if (cur2Enterlong) order2.m_action = "SELL";
            else order2.m_action = "BUY";
            order.m_totalQuantity = curOrderQty;   
            order2.m_totalQuantity = cur2OrderQty; 
            order.m_orderType = "MKT";
            order2.m_orderType = "MKT";
            long threadId = Thread.currentThread().getId();
            logger.log(Level.INFO," state enter postion: " + position +
                " inflightOrder: " + inflightOrder +
                " position2: " + position2 +
                " inflightOrder2: " + inflightOrder2 +
                " positionID: " + positionID +
                " m_action: " + order.m_action +
                " m_action(2): " + order2.m_action +
                " m_qty: " + order.m_totalQuantity +
                " m_qty2: " + order2.m_totalQuantity +    
                " exiting: " + exiting + " threadId: " + threadId + " order: " +
                    order);
            inflightOrderID=nextOrderId;
            m_s.placeOrder(nextOrderId, con, order);
            nextOrderId++;
            inflightOrderID2=nextOrderId;
            m_s.placeOrder(nextOrderId, con2, order2);
            nextOrderId++;
        }
        
        private synchronized void calcStats() {
            DescriptiveStatistics stats = new DescriptiveStatistics();
            int totalBars=tradeBars.size();
            tradeBar singleBar;
            
            for (int i=totalBars-300; i<totalBars; i++) {
                singleBar = (tradeBar)tradeBars.get(i);
                stats.addValue(singleBar.AskClose);
            } //for  
            ma = stats.getMean();
            stdev= stats.getStandardDeviation();  
            double testmin=stats.getMin();
            double testmax=stats.getMax();
            logger.log(Level.SEVERE," - " + ma + " " + stdev + " " + testmin + " "
                    + testmax); // remove when tested
        }
        
        private synchronized void dumpbars(int count) {
            int totalBars=tradeBars.size();
            tradeBar singleBar;
            for (int i=totalBars-count; i<totalBars; i++) {
                singleBar = (tradeBar)tradeBars.get(i);
                logger.log(Level.INFO,"checkbars: " + singleBar.bartime +
                        " " + singleBar.AskClose + " " + singleBar.BidClose);
            }
        }
        
        private synchronized tradeBar getLastBar() {
            int listsize = tradeBars.size();
            if (listsize>0)
                lastbar = (tradeBar) tradeBars.get(tradeBars.size() - 1);
            return lastbar;
        }
        
        private synchronized boolean overRunStop() {
            boolean overStop=false;
            tradeBar lastbar=getLastBar();
            double synPairBid;
            if ((lastbar.BidClose!=0) && (lastbar.BidClose2!=0)) {
                synPairBid=lastbar.BidClose/lastbar.BidClose2;
                if (longOrderR && (synPairBid<synPairStop)) overStop=true;
                if (!longOrderR && (synPairBid>synPairStop)) overStop=true;
            }
            return overStop;
        }
        
        private synchronized void checkstrategy(int reqId, 
                long bartime, double barclose) {
            double upperLimit=0; // for price bands
            double lowerLimit=0;
            String secs = secondsText.format(bartime*1000);
            tradeBar bar = null;
            long threadId = Thread.currentThread().getId();
            logger.log(Level.INFO," state enter postion: " + position +
                " inflightOrder: " + inflightOrder + 
                " position2: " + position2 +
                " inflightOrder2: " + inflightOrder2 +
                " exiting: " + exiting + " threadId: " + threadId);
            int listsize = tradeBars.size();
            long lastbartime=0;
            if (listsize>0) {
                lastbar = getLastBar();
                lastbartime=lastbar.bartime;
            }
            logger.log(Level.INFO,"rtbar: " + reqId + " " + bartime + " " +
                    barclose);
            // get any "top of the minute, or not! would do it 5 times a min 
            if (secs.equals("00")) /* || secs.equals("01") || secs.equals("02") ||
                    secs.equals("03") || secs.equals("04")) */ {
                    
                    logger.log(Level.INFO,"top of minute");
                    // tested .... worked .. System.exit(2);
                    // need a new bar or add to the old one
                    if (lastbartime!=bartime) {
                        bar = new tradeBar();
                        tradeBars.add( bar );
                        bar.BidClose=0;
                        bar.AskClose=0;
                        logger.log(Level.INFO,"added new tradebar");
                    } else {
                        if (lastbar!=null) {
                            bar=lastbar;
                        }
                    }
                    bar.bartime=bartime;  
                    // todo ... need to recheck this...
                    if (reqId==BIDTickerID) bar.BidClose=barclose;
                    if (reqId==BIDTickerID2) bar.BidClose2=barclose;
                    if (reqId==ASKTickerID) bar.AskClose=barclose;
                    
                    count++;
                    if (count==100) {
                        dumpbars(30);
                    }
       
                    // set indicators and signals
                    if (listsize>300) {  // only do these until we have enough bars
                        // add in the future... get the historical bars before we 
                                  
                    calcStats();
        
                    bar.buyflag=false;
                    bar.sellflag=false;
                    if (reqId == ASKTickerID) { // only check when we are doing asks
                        if (bar.AskClose > ma) { // price floating above ma
                           lowerLimit=ma + stdev*ParmInner;
                           upperLimit=ma + stdev*ParmOutter;
                           if ((bar.AskClose > lowerLimit) && (bar.AskClose < upperLimit))
                               bar.sellflag=true; 
                        } else { // floating below ma
                           upperLimit=ma - stdev*ParmInner;
                           lowerLimit=ma - stdev*ParmOutter;
                           if ((bar.AskClose > lowerLimit) && (bar.AskClose < upperLimit))
                               bar.buyflag=true;                            
                        }
                    }
                                    
                    if (bar.buyflag && bar.sellflag) 
                        logger.log(Level.SEVERE,"both buy and sell flags set");
                    
                    // check exits
                    // TODO  to be done                    
                    
                    logger.log(Level.INFO,"data: " + reqId + 
                            " b.bartime: " + bar.bartime + 
                            " barclose: " + barclose + " b.buyflag: " + bar.buyflag +
                            " b.sellflag: " + bar.sellflag +                            
                            " ma: " + ma + " stdev: " + stdev +
                            " upper: " + upperLimit + " lower: "  + lowerLimit +
                            " position: " +  position +                            
                            " inflightOrder: " + inflightOrder +
                            " position2: " +  position2 +                            
                            " inflightOrder2: " + inflightOrder2
                            );                    
                    
                    // trade on signals
                    if (bar.buyflag && !position && !inflightOrder &&
                            !position2 && !inflightOrder2) {
                        // todo ... calculate stops  exitStop = min-stopratio;
                        exitTime = bartime+fixedExitTime;
                        logger.log(Level.INFO,"show exit time, bartime: " +
                                bartime + " fixedExitTime: " + fixedExitTime +
                                " exitTime: " + exitTime);
                        takePosition();
                        longOrderR=true;
                    }
                    
                    if (bar.sellflag && !position && !inflightOrder &&
                            !position2 && !inflightOrder2) {
                        // todo ... calculate stops  exitStop = min-stopratio;
                        exitTime = bartime+fixedExitTime;
                        logger.log(Level.INFO,"show exit time, bartime: " +
                                bartime + " fixedExitTime: " + fixedExitTime +
                                " exitTime: " + exitTime);
                        takePosition();
                        longOrderR=false;
                    }
                   
                    
                    //exit position - decide if we need to exit this position
                    // todo... simplify code
                    // -- exit both if timer expired
                    // -- exit both is synthetic pair over ran stop
                    boolean doexit=false;
                    if (!exiting && (position || position2)) {
                        if (bartime>exitTime) {
                            doexit=true;
                            exitReason="expired"; 
                            exiting=true;
                            exiting2=true;
                            logger.log(Level.INFO,"do exit, reason: " + exitReason +
                                " exitTime: " + exitTime + " longOrder: " + longOrder);
                            exitPosition();
                            
                        } else { 
                            if (overRunStop()) {
                                doexit=true;
                                exitReason="stopped";
                                exiting=true;
                                exiting2=true;
                                logger.log(Level.INFO,"do exit, reason: " + exitReason +
                                   " exitTime: " + exitTime + " longOrder: " + longOrder);
                                exitPosition();
                            }
                        } // else    
                    } // decide on exits
                } // on the minute boundry 
            
        logger.log(Level.INFO," state leave postion: " + position +
                " inflightOrder: " + inflightOrder + 
                " position2: " + position2 +
                " inflightOrder2: " + inflightOrder2 +
                " exiting: " + exiting + " threadId: " + threadId);                  
        }
        }
        
        public class StartSocketServer implements Runnable {
            int clientNumber = 0; 
            ServerSocket listener;
            Logger logger;
            fx3 fx3Impl;

            public StartSocketServer(fx3 fx3Impl, Logger logger) {
                this.fx3Impl = fx3Impl;
                logger.log(Level.INFO,"StartSocketServer init");
            }
            
            @Override
            public void run() {
                Logger.getLogger(fx3.class.getName()).log(Level.INFO,
                        "StartSocketServer run started");
                try {
                    listener = new ServerSocket(9898);
                    
                    Logger.getLogger(fx3.class.getName()).log(Level.INFO,
                            "listner started");
                    while (true) {
                        new SocketResponder(listener.accept(), clientNumber++,
                            fx3Impl).run();
                    }
                                     
                } catch (Throwable ex) {
                    Logger.getLogger(fx3.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
        }
        
        // ****** setup Socket Responder here....
        private static class SocketResponder extends Thread {
            private Socket socket;
            private int clientNumber;
            fx3 fx3Impl;

            public SocketResponder(Socket socket, int clientNumber,
                    fx3 fx3Impl) {
                this.fx3Impl = fx3Impl;
                this.socket = socket;
                this.clientNumber = clientNumber;
                System.out.println("New connection with client# " +
                    clientNumber + " at " + socket);
            }

            /**
             * Services this thread's client by first sending the
             * client a welcome message then repeatedly reading strings
            * and sending back the capitalized version of the string.
            */
        public void run() {
            try {

                // Decorate the streams so we can send characters
                // and not just bytes.  Ensure output is flushed
                // after every newline.
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(socket.getInputStream()));
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                
                Logger.getLogger(fx3.class.getName()).log(Level.INFO,
                            "sock ctrl started, client # ", + clientNumber);
                
                // Send a welcome message to the client.
                out.println("hello " + clientNumber);
                

                // Get messages from the client, line by line; return them
                // capitalized
                while (true) {
                    String input = in.readLine();
                    System.out.println("inbound: " + clientNumber + " : " +
                            input);
                    if (input == null || input.equals(".")) { // exit cmd
                        Logger.getLogger(fx3.class.getName()).log(Level.INFO,
                            "exit of socket ctrl # ", + clientNumber);
                        break;
                    }
                    if (input.equals("status")) {       // status cmd
                        out.println("not completed");
                    } else if (input.equals("version")) { // version cmd
                        out.println(fx3Impl.getversion());
                    } else if (input.equals("recon")) { // version cmd
                        fx3Impl.reConnect();
                        out.println("done");
                    } else if (input.equals("execs")) { // for testing - 
                        fx3Impl.reqExecutions();
                        out.println("execs requested");
                    } else if (input.equals("crash")) {   // crash cmd
                        Logger.getLogger(fx3.class.getName()).log(Level.SEVERE,
                            "socket ctrl crash");
                        // TODO... catch exceptions in this code
                        // I think fx3Impl was previously null, wasn't caught
                        fx3Impl.crashdump("socket ctrlr crash");
                    } else {                            // default response
                        //out.println(input.toUpperCase());
                        out.println("invalid");
                    }
                }
            } catch (IOException e) {
                System.out.println("Error handling client# " + 
                        clientNumber + ": " + e);
            } finally {
                try {
                    socket.close();
                } catch (IOException e) {
                     System.out.println("Couldn't close a socket, what's going on?");
                }
                 System.out.println("Connection with client# " + 
                        clientNumber + " closed");
            }
        }
        } // soocket responder
        
        private void readParamFile(String propFileName) throws IOException {
            String line;
            InputStream inputStream;
            try {
                Properties prop = new Properties();
 
                inputStream = new FileInputStream(propFileName);
 
                if (inputStream != null) {
                        prop.load(inputStream);
                        inputStream.close();
                } else {
                        throw new FileNotFoundException("property file '" + 
                                propFileName + "' not found in the classpath");
                }                
                // general properties
                stratName = prop.getProperty("stratName");
                logger.log(Level.INFO,"prop-stratName: " + stratName);
                stratType = prop.getProperty("stratType");
                logger.log(Level.INFO,"prop-stratType: " + stratType);
                stratDescription = prop.getProperty("stratDescription");
                logger.log(Level.INFO,"prop-stratDescription: " + stratDescription);
                stratEnviron = prop.getProperty("stratEnviron");
                logger.log(Level.INFO,"prop-stratEnviron: " + stratEnviron);
                stratPortStr = prop.getProperty("stratPort");
                stratPort = Integer.parseInt(stratPortStr);
                logger.log(Level.INFO,"prop-stratPort: " + stratPort);
                logLevel = prop.getProperty("logLevel");
                logger.log(Level.INFO,"prop-logLevel: " + logLevel);
                logFile = prop.getProperty("logFile"); // we may not use
                logger.log(Level.INFO,"prop-logFile: " + logFile);
                
                // curR  reference currency
                curRBase = prop.getProperty("curRBase");
                logger.log(Level.INFO,"prop-curRBase: " + curRBase);
                curRQuote= prop.getProperty("curRQuote");
                logger.log(Level.INFO,"prop-curRQuote: " + curRQuote);
                curRConIDStr = prop.getProperty("curRConID");
                curRConID = Integer.parseInt(curRConIDStr);
                logger.log(Level.INFO,"prop-cur2ConID: " + curRConID);
                
                // cur1 first synthetic pair
                curBase = prop.getProperty("curBase");
                logger.log(Level.INFO,"prop-curBase: " + curBase);
                curQuote = prop.getProperty("curQuote");
                logger.log(Level.INFO,"prop-curQuote: " + curQuote);
                curConIDStr = prop.getProperty("curConID");
                curConID = Integer.parseInt(curConIDStr);
                logger.log(Level.INFO,"prop-curConID: " + curConID); 
                curOrderQtyStr = prop.getProperty("curOrderQty");
                curOrderQty = Integer.parseInt(curOrderQtyStr);
                logger.log(Level.INFO,"prop-curOrderQty: " + curOrderQty); 
                curNormalBuyStr = prop.getProperty("curNormalBuy");;
                curNormalBuy = Boolean.parseBoolean(curNormalBuyStr);
                logger.log(Level.INFO,"prop-curNormalBuy: " + curNormalBuy);
                
                // cur2 
                cur2Base = prop.getProperty("cur2Base");
                logger.log(Level.INFO,"prop-cur2Base: " + cur2Base);
                cur2Quote= prop.getProperty("cur2Quote");
                logger.log(Level.INFO,"prop-cur2Quote: " + cur2Quote);
                cur2ConIDStr = prop.getProperty("cur2ConID");
                cur2ConID = Integer.parseInt(cur2ConIDStr);
                logger.log(Level.INFO,"prop-cur2ConID: " + cur2ConID);                
                cur2OrderQtyStr = prop.getProperty("cur2OrderQty");
                cur2OrderQty = Integer.parseInt(cur2OrderQtyStr);
                logger.log(Level.INFO,"prop-cur2OrderQty: " + cur2OrderQty);               
                cur2NormalBuyStr = prop.getProperty("cur2NormalBuy");;
                cur2NormalBuy = Boolean.parseBoolean(cur2NormalBuyStr);
                logger.log(Level.INFO,"prop-cur2NormalBuy: " + cur2NormalBuy);
                
                // parameters
                ParmOutterStr = prop.getProperty("ParmOutter");
                ParmOutter = Double.parseDouble(ParmOutterStr);
                logger.log(Level.INFO,"prop-stratName: " + ParmOutter);
                ParmInnerStr = prop.getProperty("ParmInner");
                ParmInner = Double.parseDouble(ParmInnerStr);
                logger.log(Level.INFO,"prop-ParmInner: " + ParmInner);                
                ParmStopStr = prop.getProperty("ParmStop");
                ParmStop = Double.parseDouble(ParmStopStr);
                logger.log(Level.INFO,"prop-ParmStop: " + ParmStop);
                
            } catch (Exception e) {
                logger.log(Level.SEVERE,"propertyfile: " + e);
            } // end of try
        } // end of read parms
        
        public static void main(String[] args) {
 
            Thread.setDefaultUncaughtExceptionHandler(new Thread.
            UncaughtExceptionHandler() {
            public void uncaughtException(Thread t, Throwable e) {
                System.out.println("def.exp.handler" + t + 
                        " throws exception: " + e);
                }
            });
                        
            try {
                new fx3().run(args);
            } catch (Throwable ex) {
                System.err.println("Uncaught exception - " + ex.getMessage());
                ex.printStackTrace(System.err);
            }

        }

        private void run(String[] args) {
            try {
                  // This block configure the logger with handler and formatter
                  fhLogger = new FileHandler("fx3.log", true);
                  logger.addHandler(fhLogger);
                  logger.setLevel(Level.ALL);
                  SimpleFormatter formatter = new SimpleFormatter();
                  fhLogger.setFormatter(formatter);

                  // the following statement is used to log any messages   
                  logger.log(Level.INFO,"logger started");

                  } catch (SecurityException e) {
                    e.printStackTrace();
                  } catch (IOException e) {
                    e.printStackTrace();
                  }   
            logger.log(Level.INFO,"Prog Version: " + progversion);
            String argslist="";
            for(int i=0; i<args.length; ++i){
                argslist += args[i] + "-";
            }
            logger.log(Level.INFO,"args: -" + argslist);
            logger.log(Level.INFO,"System Environment: " + 
                System.getProperty("java.version") + " - " +
                System.getProperty("os.arch") + " - " +
                System.getProperty("os.name") + " - " +
                System.getProperty("java.vendor"));
            // testing library problem... remove code when done
            DescriptiveStatistics stats = new DescriptiveStatistics();
            stats.addValue(1);
            stats.addValue(2); 
            stats.addValue(2); 
            double testmean = stats.getMean();
            double teststdev = stats.getStandardDeviation(); 
            // =---------------------------------------

            try {
                // read parameter file
                readParamFile(args[0]);
            } catch (IOException ex) {
                Logger.getLogger(fx3.class.getName()).log(Level.SEVERE, null, ex);
            }
            // new socketserver=  keep this in the future?  now?
            //StartSocketServer socketserver = 
            // socketserver.run();
            //new StartSocketServer(this,logger).run();
            long now = System.currentTimeMillis();
            lastMktBarTime = now/1000 + 10; // set artfcl last bar 10 sec future
            try {
                StartSocketServer sockserver = new StartSocketServer(this,logger);
                if (sockserver==null)
                    logger.log(Level.SEVERE,"sockserver null");
                logger.log(Level.INFO,"creating thread");
                Thread socketserverThread = new Thread(sockserver);
                if (socketserverThread==null)
                    logger.log(Level.SEVERE,"sockserverThread null");
                logger.log(Level.INFO,"starting thread");
                socketserverThread.start();
            } catch (Throwable t) {
                logger.log(Level.SEVERE,"failed socket server start " +
                        t.getMessage());
            }
            logger.log(Level.INFO,"starting IB eConnect");
            m_s.eConnect("localhost", 4002, 10); // parm
            try {Thread.sleep(5000);} catch (InterruptedException e) {
                e.printStackTrace(); }
            m_s.reqCurrentTime();            
            logger.log(Level.INFO,"IB connected test: " + m_s.isConnected());
            int serverVersion=m_s.serverVersion();
            logger.log(Level.INFO,"server version: " + serverVersion);
            m_s.reqAccountSummary(100, "All", "TotalCashValue");
            // setup reference contract
            // curRBase; curRQuote; curRConID;
            conR = new Contract();
            conR.m_exchange="IDEALPRO";
            conR.m_localSymbol=curRBase;
            conR.m_currency=curRQuote;  
            conR.m_conId=curRConID;
            // setup contract 1
            con = new Contract();
            con.m_exchange="IDEALPRO";
            con.m_localSymbol=curBase;
            con.m_currency=curQuote;  
            con.m_conId=curConID;
            // setup contract 2
            con2 = new Contract();
            con2.m_exchange="IDEALPRO";
            con2.m_localSymbol=cur2Base;
            con2.m_currency=cur2Quote;  
            con2.m_conId=cur2ConID;

            logger.log(Level.INFO,"setting up mkt data timer");
            
            RequestMktData();
            // start scheduled thread
            SchedExecutor = new ScheduledThreadPoolExecutor(2);
            SchedExecutor.scheduleWithFixedDelay(new SchedWorkerThread(m_s, logger, this), 
                    1, 1, TimeUnit.SECONDS); // start in < 10, every <10 hours
            // run forever
            while (true) {
                try {Thread.sleep(360000);} 
                    catch (InterruptedException e) {
                        System.out.println("sleep: interrupted: " + 
                        " throws exception: " + e);
                    }
                    catch (Throwable e) {
                        System.out.println("sleep: throwable: " + 
                        " throws exception: " + e);
                    }
                
            }
        }

        private static class SchedWorkerThread implements Runnable {
            EClientSocket m_s;
            Logger logger;
            fx3 thewrapper;
            
            public SchedWorkerThread(EClientSocket m_s_conn, Logger logger_pass, 
                    fx3 wrapper) {
                // init
                m_s = m_s_conn;
                logger = logger_pass;
                thewrapper = wrapper;
            }

            @Override
            public void run() {
                // this is one of the many things that could be done...
                thewrapper.checkForLateMktData();               
            }
        }

        public synchronized void checkForLateMktData() {
                long now = System.currentTimeMillis();
                long threadId = Thread.currentThread().getId();
                //logger.log(Level.INFO,"before: now - " + 
                        //now/1000 + " last - " + lastMktBarTime);
                if ((now/1000 - lastMktBarTime) > 100   ) { // 5 sec late?
                    latetimer--;    // drop one increment 
                    if (latetimer==0) { // do this at zerp
                        logger.log(Level.INFO," late bars: now - " + 
                            now + " last - " + lastMktBarTime + 
                                " threadId: " + threadId);                    
                        reConnect();
                        RequestMktData();
                        latetimer=latetimer_default; // rst for nxt late period
                    }
                    //todo: may need to skip request if lost connection...
                }
        }
        
        @Override public synchronized void nextValidId(int orderId) {
            logger.log(Level.INFO, "nextValidID: {0}", orderId);
            nextOrderId = orderId;
        }

        @Override public void error(Exception e) {
            logger.log(Level.WARNING,"error: with exception");
            e.printStackTrace(System.out);
        }

        @Override public void error(int id, int errorCode, String errorMsg) {
            logger.log(Level.WARNING,"error: " + id +
                    " " + errorCode + " " + errorMsg);
        }

        @Override public void connectionClosed() {
            logger.log(Level.INFO,"connectionClosed");
            connectionOpen=false;  // assume this indicates connection closed
        }

        @Override public void error(String str) {
            logger.log(Level.WARNING,"error: " + str);
        }

        @Override public void tickPrice(int tickerId, int field, 
                double price, int canAutoExecute) {
            logger.log(Level.WARNING,"uncaught method ");
        }

        @Override public void tickSize(int tickerId, int field, int size) {
            logger.log(Level.WARNING,"uncaught method ");
        }

        @Override public void tickOptionComputation(int tickerId, int field, 
                double impliedVol, double delta, double optPrice, 
                double pvDividend, double gamma, double vega, double theta, 
                double undPrice) {
            logger.log(Level.WARNING,"uncaught method ");
        }

        @Override public void tickGeneric(int tickerId, int tickType, double value) {
            logger.log(Level.WARNING,"uncaught method ");
        }

        @Override public void tickString(int tickerId, int tickType, String value) {
            logger.log(Level.WARNING,"uncaught method ");
        }

        @Override public void tickEFP(int tickerId, int tickType, double basisPoints, 
                String formattedBasisPoints, double impliedFuture, int holdDays, 
                String futureExpiry, double dividendImpact,
                        double dividendsToExpiry) {
            logger.log(Level.WARNING,"uncaught method ");
        }

        @Override public synchronized void orderStatus(int orderId, String status, int filled, 
                int remaining, double avgFillPrice, int permId, int parentId, 
                double lastFillPrice, int clientId, String whyHeld) {
            boolean wasAnExit=false; // set to true if this is a position exit
            long fillTime;
            long positionHoldTime;
            long threadId = Thread.currentThread().getId();
            logger.log(Level.INFO,"orderId: " + orderId + " status: " + status +
                    " filled: " + filled + " remaining: " + remaining +
                    " positionID: " + positionID +
                    " exiting: " + exiting +
                    " exiting2: " + exiting2 +
                    " clientID: " + clientId + " whyHeld: " + whyHeld);
            // todo 2security changes here also
            if (remaining == 0) {  // determine which half of the exit
                fillTime = System.currentTimeMillis();
                positionHoldTime = (fillTime - entryTime)/1000;
                if (inflightOrderID==orderId) {
                    inflightOrderID=0;
                    if (!fullExit) { // state ch filld buy/sell  (was !exiting
                        position=true;  // for entry
                        inflightOrder=false;
                        entryPrice=avgFillPrice;
                        logger.log(Level.INFO,
                            "position(1) filled orderId: " + orderId);
                    } else if (position) { // state ch filld exit
                        position=false; // for exit
                        exiting=false;  //
                        inflightOrder=false;
                        exitPrice=avgFillPrice;
                        wasAnExit=true;
                        logger.log(Level.INFO,
                            "position(1) filled (exit) orderId: " + orderId); 
                    }
                } // if (inflight(1)..
                if (inflightOrderID2==orderId) {
                    inflightOrderID2=0;
                    if (!fullExit) { // state ch filld buy/sell ( was !exiting2
                        position2=true;  // for entry
                        inflightOrder2=false;
                        entryPrice2=avgFillPrice;
                        logger.log(Level.INFO,
                            "position2 filled orderId: " + orderId);
                    } else if (position2) { // state ch filld exit
                        position2=false; // for exit
                        exiting2=false;  //
                        inflightOrder2=false;
                        exitPrice2=avgFillPrice;
                        wasAnExit=true;
                        logger.log(Level.INFO,
                            "position2 filled (exit) orderId: " + orderId); 
                    }
                } // if (inflight2...
                if (!position && !position2 && fullExit) {
                    fullExit=false;                
                    if (wasAnExit) {
                        // calc profit , by leg and then sum the legs
                        // get account balance? or logs from sever
                        logger.log(Level.INFO, "offical exit record, orderId: " +
                            orderId + " longOrder: " + longOrder +
                                " entryPriceMkt: " + entryPriceMkt + 
                                " entryPrice: " + entryPrice +
                                " entryPrice2: " + entryPrice2 +
                                " exitPrice: " + exitPrice +
                                " exitPrice2: " + exitPrice2 +
                                //" lastFillPrice: " + lastFillPrice +
                                //" avgFillPrice: " + avgFillPrice +
                                " entryTime: " + entryTime + // when order was
                                " exitTime: " + fillTime + // now!
                                " positionHoldTime(secs): " + positionHoldTime +
                                            // calculate position time
                                " exitReason: " + exitReason +
                                " positionID: " + positionID
                                );                    
                    } // if (wasAnExit)
                }
                // implied else, no inflightOrder... do nothing
            } // if (remaining
            // here, if filled is not 0 too many times we may have an issue
            // ... we could maybe cancel remaining order?
            if (wasAnExit && !exiting && !exiting2)
                logger.log(Level.INFO," positionID closed: " + positionID);
            logger.log(Level.INFO," state leaving postion: " + position +
                    " inflightOrder: " + inflightOrder +
                    " postion2: " + position2 +
                    " inflightOrder2: " + inflightOrder2 +
                    " exiting: " + exiting + " exiting2: " + exiting2 + 
                    " threadId: " + threadId);                    
        }

        @Override public void openOrder(int orderId, Contract contract, 
                Order order, OrderState orderState) {
            logger.log(Level.INFO,"orderId: " + orderId + " order status: " +
                    orderState.m_status + " warningmsg: " + 
                    orderState.m_warningText);
        }

        @Override public void openOrderEnd() {
            logger.log(Level.INFO,"openOrderEnd, no parms");
        }

        @Override public void updateAccountValue(String key, String value, 
                String currency, String accountName) {
            logger.log(Level.WARNING,"uncaught method ");
        }

        @Override public void updatePortfolio(Contract contract, int position, 
                double marketPrice, double marketValue, double averageCost, 
                double unrealizedPNL, double realizedPNL, String accountName) {
            logger.log(Level.WARNING,"uncaught method ");
        }

        @Override public void updateAccountTime(String timeStamp) {
            logger.log(Level.WARNING,"uncaught method ");
        }

        @Override public void accountDownloadEnd(String accountName) {
            logger.log(Level.WARNING,"uncaught method ");
        }

        @Override public void contractDetails(int reqId, 
                ContractDetails contractDetails) {
            logger.log(Level.WARNING,"uncaught method ");
        }

        @Override public void bondContractDetails(int reqId, 
                ContractDetails contractDetails) {
            logger.log(Level.WARNING,"uncaught method ");
        }

        @Override public void contractDetailsEnd(int reqId) {
            logger.log(Level.WARNING,"uncaught method ");
        }

        @Override public void execDetails(int reqId, Contract contract, Execution execution) {
            Execution e = execution;
            logger.log(Level.INFO,reqId + " " + contract.m_conId + " " +
                    e.m_avgPrice + " " + e.m_clientId  + " " + e.m_cumQty  + " " +
                    e.m_exchange + " " + e.m_execId  + " " + 
                    e.m_liquidation + " " +
                    e.m_orderId + " " +
                    e.m_permId + " " +
                    e.m_price + " " +
                    e.m_shares + " " +
                    e.m_side + " " +
                    e.m_time + " " +
                    e.m_evRule + " " +
                    e.m_evMultiplier);
        }

        @Override public void execDetailsEnd(int reqId) {
            logger.log(Level.WARNING,"uncaught method ");
        }

        @Override public void updateMktDepth(int tickerId, int position, 
                int operation, int side, double price, int size) {
            logger.log(Level.WARNING,"uncaught method ");
        }

        @Override public void updateMktDepthL2(int tickerId, int position, 
                String marketMaker, int operation, int side, double price, int size) {
            logger.log(Level.INFO,"updateMktDepthL2: ");
        }

        @Override public void updateNewsBulletin(int msgId, int msgType, 
                String message, String origExchange) {
            logger.log(Level.INFO,"updateNewsBulletin: " + msgId + " " + msgType
                + " " + message + " " + origExchange);
        }

        @Override public void managedAccounts(String accountsList) {
            logger.log(Level.INFO,accountsList);
        }

        @Override public void receiveFA(int faDataType, String xml) {
            logger.log(Level.WARNING,"uncaught method ");
        }

        @Override public void historicalData(int reqId, String date, double open, 
                double high, double low, double close, int volume, int count, 
                double WAP, boolean hasGaps) {
            logger.log(Level.WARNING,"uncaught method ");
        }

        @Override public void scannerParameters(String xml) {
            logger.log(Level.WARNING,"uncaught method ");
        }

        @Override public void scannerData(int reqId, int rank, ContractDetails contractDetails, 
                String distance, String benchmark, String projection, String legsStr) {
            logger.log(Level.WARNING,"uncaught method ");
        }

        @Override public void scannerDataEnd(int reqId) {
            logger.log(Level.WARNING,"uncaught method ");
        }

        @Override public synchronized void realtimeBar(int reqId, long time, double open, 
                double high, double low, double close, long volume, double wap, 
                int count) {
            logger.log(Level.INFO,"realtimeBar: reg " + reqId + " time " +
                    time + " close " + close);
            checkstrategy(reqId, time, close );
            lastMktBarTime = time;  // save to determine if bars are overdue
        }

        @Override public void currentTime(long time) {
            logger.log(Level.INFO,"currentTime: " + time);
            connectionOpen=true;  // assume this indicates connection open
        }

        @Override public void fundamentalData(int reqId, String data) {
            logger.log(Level.WARNING,"uncaught method ");
        }

        @Override public void deltaNeutralValidation(int reqId, UnderComp underComp) {
            logger.log(Level.WARNING,"uncaught method ");
        }

        @Override public void tickSnapshotEnd(int reqId) {
            logger.log(Level.WARNING,"uncaught method ");
        }

        @Override public void marketDataType(int reqId, int marketDataType) {
            logger.log(Level.WARNING,"uncaught method ");
        }

        @Override public void commissionReport(CommissionReport commissionReport) {
            CommissionReport c = commissionReport;
            logger.log(Level.INFO,c.m_commission + " " + 
                    c.m_currency + " " + c.m_execId + " " + c.m_realizedPNL + 
                    c.m_yield + " " + c.m_yieldRedemptionDate);
        }

        @Override public void position(String account, Contract contract, int pos, 
                double avgCost) {
            logger.log(Level.WARNING,"uncaught method ");
        }

        @Override public void positionEnd() {
            logger.log(Level.WARNING,"uncaught method ");
        }

        @Override public void accountSummary(int reqId, String account, String tag, 
                String value, String currency) {
            logger.log(Level.INFO,"reqID, acc, tag, val, crrncy: " +
                    account + " " + tag + " " + value + " " + currency);
        }

        @Override public void accountSummaryEnd(int reqId) {
            logger.log(Level.INFO,"account summary end: " + reqId);
        }

        @Override public void verifyMessageAPI( String apiData) {
            logger.log(Level.WARNING,"uncaught method ");
        }

        @Override public void verifyCompleted( boolean isSuccessful, String errorText){
            logger.log(Level.WARNING,"uncaught method ");
        }

        @Override public void displayGroupList( int reqId, String groups){
            logger.log(Level.WARNING,"uncaught method ");
        }

        @Override public void displayGroupUpdated( int reqId, String contractInfo){
            logger.log(Level.WARNING,"uncaught method ");
        }

}