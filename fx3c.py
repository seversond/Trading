"""
   iblib.py

"""

import sys
import argparse
import datetime
import collections
import inspect

import logging
import time
import os.path
from statistics import mean
from statistics import stdev
#import pandas pd

from ibapi import wrapper
from ibapi.client import EClient
from ibapi.utils import iswrapper

# types
from ibapi.common import *
from ibapi.order_condition import *
from ibapi.contract import *
from ibapi.order import *
from ibapi.order_state import *
from ibapi.execution import Execution
from ibapi.execution import ExecutionFilter
from ibapi.scanner import ScannerSubscription
from ibapi.ticktype import *

from ibapi.account_summary_tags import *

from ibapi_grease import patch_all
patch_all()

import iblib
import configib

import oms

class fx3cApp(iblib.IBWrapper, iblib.IBClient):
    def __init__(self,config):
        iblib.IBWrapper.__init__(self)
        iblib.IBClient.__init__(self, wrapper=self)
        self.config=config
        self.nKeybInt = 0
        self.started = False
        self.state="seekingEntry"
        self.nextValidOrderId = None
        self.permId2ord = {}
        #self.reqId2nErr = collections.defaultdict(int)
        self.globalCancelOnly = False
        self.ASKTickerID=301      # next three for parm file at some point
        self.firstBarTesting=True  # set to false for production
        self.bars={}
        self.bars['ask']=[]
        self.BIDTickerID=401
        self.bars['bid']=[]
        import collections
        self.dat = collections.deque(maxlen=300)
        self.win_size=300 # todo param this and make sure it is on the "right" intervals
        self.seeking_entry=True # assume we start out without a position
        self.seeking_exit=False # assume we start out without a position
        # -- order related stuff
        self.order_status={}
        self.order_status['active_order']=False
        self.con=self.setup_contract()    # todo probably do this 2x.. bars and orders, do once
        print("App initialized")

    def connectAck(self):
        if self.async:
            self.startApi()
        print("connectAck")
        #self.oms=oms(self)    --- plan to add later
        self.realTimeBars_cancel()   # cancelling just in case
        self.realTimeBars_req()
        self.miscelaneous_req()

    def keyboardInterrupt(self):
        self.nKeybInt += 1
        if self.nKeybInt == 1:
            self.stop()
        else:
            print("Finishing test")
            self.done = True

    def stop(self):
        print("Executing cancels")
        self.realTimeBars_cancel()
        print("Executing cancels ... finished")

    def nextOrderId(self):
        oid = self.nextValidOrderId
        self.nextValidOrderId += 1
        return oid

    def error(self, reqId: TickerId, errorCode: int, errorString: str):
        print("Error. Id: ", reqId, " Code: ", errorCode, " Msg: ", errorString)

    def winError(self, text: str, lastError: int):
        pass

    def MarketOrder(action:str, quantity:float):
        order = Order()
        order.action = action
        order.orderType = "MKT"
        order.totalQuantity = quantity
        return order

    def setup_contract(self):
        contract = Contract()
        contract.symbol = self.config["curR"]["base"] 
        contract.secType = "CASH"
        contract.currency = self.config["curR"]["quote"]
        contract.conId = self.config["curR"]["conID"] 
        contract.exchange = "IDEALPRO"
        return contract

    
    def proc_order_status():
        pass
    
    def placeOrder():
        pass
        # se timer to detect order delays

    def take_position(self):
        # init order_state
        # init Order order1 = new Order();
        # long threadId = Thread.currentThread().getId();
        self.seeking_entry=False
        order_status["long_position"]=long_position #  longPos = true; // need to setup state table
        order_status["positionID"] += 1  # possible conflict
        order_status["fullExit"]=True
        order_status["inflightOrder"]=True
        # pass in - tradeBar bar = getLastBar();
        if (long_position):
            longOrder=True
            order["action"] = "BUY"
            order_status["exit_action"] = "SELL"
            synPairStop=ma - stdev*ParmStop # set stop below purchase
        else:
            longOrder=False
            order["action"] = "SELL"
            order_status["exit_action"] = "BUY"
            synPairStop=ma + stdev*ParmStop # set stop above purchase
        # dup? curEnterLong=longOrder; // save for the exit
        # dup? cur2Enterlong=longOrder2; // save for the exit
        # most of setup is ready... two parts now
        order1["totalQuantity"] = curOrderQty # todo set cur qty
        #init  order1.m_orderType = "MKT";
        order_status["entryTime"] = System.currentTimeMillis()
        order_status["entryPriceMkt"]=lastbar # todo check ask? ref or real
        order_status["inflightOrderID"]=order_status["nextOrderId"]
        placeOrder(nextOrderId, con, order1)
        # ---- prep security 2
        # init order2.m_totalQuantity = curOrderQty; // todo set cur qty
        # init order2.m_orderType = "MKT";
        logger.log(Level.INFO," take postion: " + position +
            " inflightOrder: " + inflightOrder + 
            " positionID: " + positionID +
            " synPairStop: " + synPairStop +
            " m_action: " + order1.m_action +
            " m_qty: " + order1.m_totalQuantity +
            " exiting: " + exiting + " threadId: " + threadId);
        # todo need to persist order state in database?
                    
    def exit_position(self,reason): 
        # todo ... change this to exit for both securitys
        # look to a buy vs sell indicator to be setup at the "take" time
        # place trade
        self.seaking_exit=False
        order = Order()
        order_status["fullExit"]=True
        order_status["inflightOrder"]=True
        order["m_action"]=order_status["exit_action"]     
        order.m_totalQuantity = curOrderQty   
        order.m_orderType = "MKT"
        #long threadId = Thread.currentThread().getId();
        logger.log(Level.INFO," state enter postion: " + position +
            " inflightOrder: " + inflightOrder +
            " positionID: " + positionID +
            " m_action: " + order.m_action +
            " m_qty: " + order.m_totalQuantity +
            " exiting: " + exiting + " threadId: " + threadId + " order: " +
                  order)
        order_status["inflightOrderID"]=order_status["next_order_id"]+1
        placeOrder(order_status["inflicht_OID"], con, order);

    def realTimeBars_req(self):
        # Requesting real time bars
        print("requesting realtime bars - DKS")
        contract = Contract()
        contract.exchange = "IDEALPRO"
        contract.conId = self.config["curR"]["conID"]
        self.reqRealTimeBars(self.ASKTickerID,contract,5, "ASK", False, [])
        contract.conId = self.config["curR"]["conID"]
        self.reqRealTimeBars(self.BIDTickerID,contract,5, "BID", False, [])

    def histBars_req(self):
        print("requested historical bars")
        delta_seconds=5  # IB is fixed at five secsm
        contract = Contract()
        contract.exchange = "IDEALPRO"
        contract.conId = self.config["curR"]["conID"]
        self.reqHistoricalData(4001, contract, "",
            "1500 S", "5 secs", "ASK", 1, 1, False, [])
 
    def calc_stats(self):
        self.ma=mean([x[1] for x in self.bars['ask'][-self.win_size:]]) # [x[1] for x in a]    
        self.std=stdev([x[1] for x in self.bars['ask'][-self.win_size:]]) # takes second element of tuple    

    def check_strategy(self):
        self.sell_flag=False
        self.buy_flag=False
        ask=self.bars['ask'][-1:][0][1] #last ask, only element of array, second list of element
        print("ask: ",ask)
        ParmInner = self.config["parmInner"]
        ParmOutter = self.config["parmOutter"]
        if self.win_size < len(self.bars['ask']):   # enough data to evaluate, todo maybe dup to below
            if ask > self.ma:
                lower_limit = self.ma + self.std * ParmInner
                upper_limit = self.ma + self.std * ParmOutter
                if ((ask > lower_limit) and (ask < upper_limit)):
                    self.sell_flag=True
            else:
                upper_limit = self.ma - self.std * ParmInner
                lower_limit = self.ma - self.std * ParmOutter
                if ((ask > lower_limit) and (ask < upper_limit)):
                    self.buy_flag=True

    def check_entry(self):
        if self.win_size < len(self.bars['ask']):   # enough data to evaluate
            self.calc_stats()
            self.check_strategy()
            if self.state=="seekingEntry" and (self.buy_flag or self.sell_flag):
                self.take_position()

    def check_exit(self):
        if self.state=="seekingExit":
            if bar_time > exit_time:
                self.exit_position('expired')
            elif past_stop:
                self.exit_position('stopped')
            else:
                pass

    def stuff_fake_bars(self,time,close):
        for x in range(self.win_size):
            self.bars['ask'].append((time,close))

    def realtimeBar(self, reqId: TickerId, time: int, open: float, high: float,
                    low: float, close: float, volume: int, wap: float,
                    count: int):
        if reqId==self.ASKTickerID:
            if self.firstBarTesting:               # quickly preload bars to accelerate testing todo
                self.stuff_fake_bars(time,close)
                self.firstBarTesting=False
            self.bars['ask'].append((time,close))    #todo - close? or other
            if self.seeking_entry:
                self.check_entry()
        elif reqId==self.BIDTickerID:
            self.bars['bid'].append((time,close))    #todo - close? or other
            if self.seeking_exit:
                self.check_exit()
        else:
            print("ERROR: unknown requestID!")
        print("RealTimeBars. ", reqId, "Time:", time, "Open:", open,
              "High:", high, "Low:", low, "Close:", close, "Volume:", volume,
              "Count:", count, "WAP:", wap)
        # logging.info("rtbar: %d %f" % (reqId, close))
        print(len(self.bars['ask']))

    # def histBar.... here todo

    def currentTime(self,time):
        print("incoming time:", time)

    def openOrder(self, orderId: OrderId, contract: Contract, order: Order,
                 orderState: OrderState):
        super().openOrder(orderId, contract, order, orderState)
        print("OpenOrder. ID:", orderId, contract.symbol, contract.secType,
             "@", contract.exchange, ":", order.action, order.orderType,
              order.totalQuantity, orderState.status)

    def orderStatus(self, orderId: OrderId, status: str, filled: float,
                    remaining: float, avgFillPrice: float, permId: int,
                    parentId: int, lastFillPrice: float, clientId: int,
                    whyHeld: str):
        super().orderStatus(orderId, status, filled, remaining,
                           avgFillPrice, permId, parentId, lastFillPrice, clientId, whyHeld)
        print("OrderStatus. Id:", orderId, "Status:", status, "Filled:", filled,
              "Remaining:", remaining, "AvgFillPrice:", avgFillPrice,
              "PermId:", permId, "ParentId:", parentId, "LastFillPrice:", lastFillPrice, 
              "state: ", state, "ClientId:", clientId, "WhyHeld:", whyHeld)
        if remainint==0:
            print("order fully filled")
            if self.state=="takePositionOrder":
                self.state="seekingExit"
                print("state change take to seeking exit")
            elif self.state=="exitPositonOrder":
                self.state="seekingEntry"
                print("state change exit to seeking entry")
            else:
                print("state error: inconsistent state at fulkl filled order") 
 
    def openOrderEnd(self):
        super().openOrderEnd()
        print("OpenOrderEnd")

    def realTimeBars_cancel(self):
        # Canceling real time bars
        self.cancelRealTimeBars(self.ASKTickerID)
        self.cancelRealTimeBars(self.BIDTickerID)

    def miscelaneous_req(self):
        # Request TWS' current time ***/
        self.reqCurrentTime()
        # Setting TWS logging level  ***/
        # self.setServerLogLevel(1)


def main():
    # print(args)
    config=configib.read_config(sys.argv[1])  # first arg is filename of config file


    # enable logging when member vars are assigned
    from ibapi import utils
    from ibapi.order import Order
    from ibapi.contract import Contract, UnderComp
    from ibapi.tag_value import TagValue

    # from inspect import signature as sig
    # import code code.interact(local=dict(globals(), **locals()))
    # sys.exit(1)

    # tc = TestClient(None)
    # tc.reqMktData(1101, ContractSamples.USStockAtSmart(), "", False, None)
    # print(tc.reqId2nReq)
    # sys.exit(1)

    try:
        app = fx3cApp(config)
        print("starting connect")
        # app.connect("127.0.0.1", app.config["port"], clientId=app.config["clientId"])
        app.connect("127.0.0.1", app.config["port"], 15)
        print("serverVersion:%s connectionTime:%s" % (app.serverVersion(),
                                                      app.twsConnectionTime()))
        app.run()
    except:
        raise
    finally:
        #app.dumpTestCoverageSituation()
        #app.dumpReqAnsErrSituation()
        pass

if __name__ == "__main__":
    main()
