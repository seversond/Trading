# ------------------------------------
#
# fx1b.r 
# currency trading simulation
#
# ------------------------------------

library(PerformanceAnalytics)

o <- options(error=recover, show.error.locations=TRUE, warn=2);#

print("fx1b...")
print(Sys.time())
args<- commandArgs(TRUE)
pair<-args[1]
exitfile<-paste0(pair,"-exits.dat")

# set parameters for band.... may have two in the future, upper and lower 
exittimer<-as.numeric(args[2])   # looped... not real sensitive
sdInner<-as.numeric(args[3])
sdOutter<-as.numeric(args[4])
sdStop<-as.numeric(args[5])
lagwindow<-as.numeric(args[6])
entries<-data.frame()
exitrecords<-data.frame()

dorun<- function(lagwindow,sdInner,sdOutter,sdStop,exittimer) {
  filename<-paste0(pair,"-ASK.dat")
  ask<-unique(read.csv(filename,header=FALSE))
  names(ask)<-c("dtime","aOpen","aHigh","aLow","aClose","av1","av2","av3","av4")
  ask.xts<-xts(ask,order.by=as.POSIXct(ask$dtime, origin="1970-01-01"))
  filename<-paste0(pair,"-BID.dat")
  bid<-unique(read.csv(filename,header=FALSE))
  names(bid)<-c("dtime","bOpen","bHigh","bLow","bClose","bv1","bv2","bv3","bv4")
  bid.xts<-xts(bid,order.by=as.POSIXct(bid$dtime, origin="1970-01-01"))
  alldata<-merge(ask,bid,by="dtime")
  alldata$ma <- rollapply(alldata$aClose,lagwindow,mean,fill=NA)
  alldata$sd <- rollapply(alldata$aClose,lagwindow,sd,fill=NA)
  alldata$hiOutter <- alldata$ma + alldata$sd*sdOutter
  alldata$hiInner <- alldata$ma + alldata$sd*sdInner
  alldata$loInner <- alldata$ma - alldata$sd*sdInner
  alldata$loOutter <- alldata$ma - alldata$sd*sdOutter
  alldata$sellflag<-FALSE
  alldata$sellflag[(alldata$aClose > alldata$hiInner) & (alldata$aClose < alldata$hiOutter)] <- TRUE
  alldata$buyflag<-FALSE
  alldata$buyflag[(alldata$aClose < alldata$loInner) & (alldata$aClose > alldata$loOutter)] <- TRUE
  exitrecords<-data.frame()
  position<-FALSE
  head(alldata)
  write.csv(alldata,"alldata.csv")
  for (i in 1:nrow(alldata)) {
    # ----------- check for entry-----------
    if (position == FALSE) {    # only checking if there is no position
      if (alldata[i,]$sellflag == TRUE) {           # *** entry: sell
        position<-TRUE          # 
        # write a sell record
        tranprice<-alldata[i,]$aClose
        stopoffset<-alldata[i,]$sd*sdStop # do some fraction of offset based on std dev
        entry<-data.frame(type="sell",entrytime=alldata[i,]$dtime,exittime=i+exittimer, price=tranprice,
                          entrystop=tranprice+stopoffset, index=i,
				ma=alldata[i,]$ma, sd=alldata[i,]$sd, hO=alldata[i,]$hiOutter, hI=alldata[i,]$hiInner,
				lI=alldata[i,]$loInner, lO=alldata[i,]$loOutter) # build entry record
        entries<-rbind(entries,entry)       # write entry record
      } # if sellflag
      if (alldata[i,]$buyflag == TRUE) {            # *** entry: buy
        position<-TRUE
        # write a buy record
        tranprice=alldata[i,]$aClose
        stopoffset<-alldata[i,]$sd*sdStop # do some fraction of offset based on std dev
        entry<-data.frame(type="buy",entrytime=alldata[i,]$dtime,exittime=i+exittimer, price=tranprice,
                          entrystop=tranprice-stopoffset, index=i,
				ma=alldata[i,]$ma, sd=alldata[i,]$sd, hO=alldata[i,]$hiOutter, hI=alldata[i,]$hiInner,
				lI=alldata[i,]$loInner, lO=alldata[i,]$loOutter) # build entry record
        entries<-rbind(entries,entry)       # write entry record
      } # if buyflag
      
      # ---------- check for exit  ----------
      
    } else { 
      # lookup current position(stored in entry) 
      if (i == entry$exittime) {                 # *** exit: by timer
        # write exit record
        exitrecord<-data.frame(type=entry$type, entrytime=entry$entrytime, exittime=alldata[i,]$dtime, index=i,
                            inprice=entry$price, price=alldata[i,]$bClose,
                               exittype="timed")
        exitrecord<-cbind(exitrecord,entry)
        exitrecords<-rbind(exitrecords,exitrecord)
        position<-FALSE
      }
      # *** exit: by stop
      if ((entry$type=="buy") & (alldata[i,]$bClose < entry$entrystop)) {  
        # exit position and write exit record
        exitrecord<-data.frame(type=entry$type,  entrytime=entry$entrytime, exittime=alldata[i,]$dtime, 
                            index=i, inprice=entry$price, price=alldata[i,]$bClose,
                               exittype="stop")
        exitrecord<-cbind(exitrecord,entry)
        exitrecords<-rbind(exitrecords,exitrecord)
        position<-FALSE
      }
      if ((entry$type=="sell") & (alldata[i,]$bClose > entry$entrystop)) {   
        # exit position and write exit record
        exitrecord<-data.frame(type=entry$type,  entrytime=entry$entrytime, exittime=alldata[i,]$dtime,
                              index=i, inprice=entry$price, price=alldata[i,]$bClose,
                               exittype="stop")
        exitrecord<-cbind(exitrecord,entry)
        exitrecords<-rbind(exitrecords,exitrecord)
        position<-FALSE
      }
    }  # if else    
  } # for
  entries$entrytimestr<-as.POSIXct(entries$entrytime, origin="1970-01-01")
  write.csv(exitrecords,"exits.csv")
  write.csv(entries,"entries.csv")
  exitrecords
} # end dorun function


# --- main -----

  print("run")
  #print(j)
  # set free param here .... sellcutratio<-j
  exitrecords<-dorun(lagwindow,sdInner,sdOutter,sdStop,exittimer)
  exitrecords$diff<-exitrecords$price-exitrecords$inprice
  exitrecords$pf <- ifelse(exitrecords$type=="sell", -1, 1)
  exitrecords$prft<- exitrecords$diff*exitrecords$pf
  #eq<-cumsum(exitrecords$prft)
  profits<-exitrecords$prft
  loss<-profits[profits<=0]
  print(mean(loss))
  gain<-profits[profits>0]
  print(mean(gain))
  avgprofits<-mean(profits)
  print(avgprofits)

paste0("fx1b-results: ", nrow(exitrecords), " ", avgprofits, " ", exittimer, " ", sdInner,
     " ", sdOutter, " ", sdStop, " ", lagwindow)

print("end")
print(Sys.time())
