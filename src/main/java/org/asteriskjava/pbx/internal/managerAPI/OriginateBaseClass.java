package org.asteriskjava.pbx.internal.managerAPI;

import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.log4j.Logger;
import org.asteriskjava.live.ManagerCommunicationException;
import org.asteriskjava.pbx.AsteriskSettings;
import org.asteriskjava.pbx.CallerID;
import org.asteriskjava.pbx.Channel;
import org.asteriskjava.pbx.EndPoint;
import org.asteriskjava.pbx.NewChannelListener;
import org.asteriskjava.pbx.PBX;
import org.asteriskjava.pbx.PBXException;
import org.asteriskjava.pbx.PBXFactory;
import org.asteriskjava.pbx.asterisk.wrap.actions.GetVarAction;
import org.asteriskjava.pbx.asterisk.wrap.actions.OriginateAction;
import org.asteriskjava.pbx.asterisk.wrap.events.BridgeEvent;
import org.asteriskjava.pbx.asterisk.wrap.events.HangupEvent;
import org.asteriskjava.pbx.asterisk.wrap.events.LinkEvent;
import org.asteriskjava.pbx.asterisk.wrap.events.ManagerEvent;
import org.asteriskjava.pbx.asterisk.wrap.events.NewChannelEvent;
import org.asteriskjava.pbx.asterisk.wrap.events.OriginateResponseEvent;
import org.asteriskjava.pbx.asterisk.wrap.events.UnlinkEvent;
import org.asteriskjava.pbx.asterisk.wrap.response.ManagerResponse;
import org.asteriskjava.pbx.internal.core.AsteriskPBX;

public abstract class OriginateBaseClass extends EventListenerBaseClass
{
    // Used to set a
    public static final String NJR_ORIGINATE_ID = "njrOriginateID"; //$NON-NLS-1$

    /*
     * this class generates and issues ActionEvents to asterisk through the
     * manager. This is the asterisk coal face.
     */
    static Logger logger = Logger.getLogger(OriginateBaseClass.class);

    private volatile String originateID;

    volatile private boolean originateSuccess;

    private final Channel monitorChannel1;

    private boolean hungup = false;

    private Channel newChannel;

    private final Channel monitorChannel2;

    private final OriginateResult result;

    /**
     * The following two variables together are used to determine if the
     * originated channel has come up. This is to overcome the problem that the
     * OriginateResponseEvent and the NewChannelEvent can occur in any order
     * (although the NewChannelEvent will occur first in most circumstances).
     * Note: we get many NewChannelEvents but we are only interested in a very
     * specific one.
     */
    // Used to track if the originate event has been seen.
    private boolean originateSeen = false;

    // Used to track if the new (final) channel has been seen.
    private boolean channelSeen = false;

    private final NewChannelListener listener;

    private final CountDownLatch originateLatch = new CountDownLatch(1);

    protected OriginateBaseClass(final NewChannelListener listener, final Channel monitor, final Channel monitor2)
    {
        super("NewOrginateClass"); //$NON-NLS-1$
        this.listener = listener;
        this.monitorChannel1 = monitor;
        this.monitorChannel2 = monitor2;
        this.result = new OriginateResult();

        // Just add us as an asterisk event listener.
        this.startListener(PBXFactory.getActivePBX());

    }

    /**
     * @param local
     * @param target
     * @param myVars
     * @param callerId the caller id to set when initiating this call.
     * @param hideCallerId
     * @param context
     * @return
     */
    protected OriginateResult originate(final EndPoint local, final EndPoint target, final HashMap<String, String> myVars,
            final CallerID callerID, final Integer timeout, final boolean hideCallerId, final String context)
    {
        OriginateBaseClass.logger.debug("originate called"); //$NON-NLS-1$
        this.originateSeen = false;
        this.channelSeen = false;

        if (this.hungup == true)
        {
            // the monitored channel already hungup so just return false and
            // shutdown
            this.close();
            return null;
        }

        OriginateBaseClass.logger.debug("originate connection endPoint \n" + local + " to endPoint " + target //$NON-NLS-1$ //$NON-NLS-2$
                + " vars " + myVars); //$NON-NLS-1$
        ManagerResponse response = null;

        final AsteriskSettings settings = PBXFactory.getActiveProfile();

        final OriginateAction originate = new OriginateAction();
        this.originateID = originate.getActionId();

        // the double under score "__" is to cause the variable to propagate
        // forward to the new channel when using local/
        myVars.put("__" + OriginateBaseClass.NJR_ORIGINATE_ID, this.originateID); //$NON-NLS-1$

        Integer localTimeout = timeout;

        if (timeout == null)
        {
            localTimeout = 30000;
            try
            {
                localTimeout = settings.getDialTimeout() * 1000;
            }
            catch (final Exception e)
            {
                OriginateBaseClass.logger.error("Invalid dial timeout value"); //$NON-NLS-1$
            }
        }

        // Whilst the originate document says that it takes a channel it
        // actually takes an
        // end point. I haven't check but I'm skeptical that you can actually
        // originate to
        // a channel as the doco talks about 'dialing the channel'. I suspect
        // this
        // may be part of asterisk's sloppy terminology.
        if (local.isLocal())
        {
            originate.setEndPoint(local);
            originate.setOption("/n"); //$NON-NLS-1$
        }
        else
        {
            originate.setEndPoint(local);
        }

        originate.setContext(context);
        originate.setExten(target);
        originate.setPriority(1);

        // Set the caller id.
        if (hideCallerId)
        {
            // hide callerID
            originate.setCallingPres(32);
        }
        else
        {
            originate.setCallerId(callerID);
        }

        originate.setVariables(myVars);
        originate.setAsync(true);
        originate.setTimeout(localTimeout);

        AsteriskPBX pbx = (AsteriskPBX) PBXFactory.getActivePBX();

        try
        {
            response = pbx.sendAction(originate, localTimeout);
            OriginateBaseClass.logger.debug("Originate.sendAction completed"); //$NON-NLS-1$
            if (response.getResponse().compareToIgnoreCase("Success") != 0)//$NON-NLS-1$
            {
                OriginateBaseClass.logger
                        .error("Error Originating call" + originate.toString() + " : " + response.getMessage());//$NON-NLS-1$//$NON-NLS-2$
                throw new ManagerCommunicationException(response.getMessage(), null);
            }

            originateLatch.await(localTimeout + 1000, TimeUnit.MILLISECONDS); // wait
                                                                              // the
                                                                              // set
                                                                              // timeout
                                                                              // +1
            // second to
            // allow for
            // asterisk to start the originate
        }
        catch (final InterruptedException e)
        {
            OriginateBaseClass.logger.debug(e, e);
        }
        catch (final Exception e)
        {
            OriginateBaseClass.logger.error(e, e);
        }
        finally
        {
            this.close();
        }

        if (this.originateSuccess == true)
        {
            this.result.setSuccess(true);
            this.result.setChannelData(this.newChannel);
            OriginateBaseClass.logger.debug("new channel ok: " + this.newChannel); //$NON-NLS-1$
        }
        else
        {
            OriginateBaseClass.logger.error("originate failed connecting endPoint: " + local + " to ext " + target); //$NON-NLS-1$//$NON-NLS-2$

            if (this.newChannel != null)
            {
                try
                {
                    pbx.hangup(this.newChannel);
                }
                catch (IllegalArgumentException | IllegalStateException | PBXException e)
                {
                    logger.error(e, e);

                }
            }
        }
        return this.result;
    }

    void abort(final String reason)
    {
        OriginateBaseClass.logger.debug("Aborting originate ");//$NON-NLS-1$
        this.close();
        this.originateSuccess = false;
        this.result.setAbortReason(reason);
        this.hungup = true;
        if (this.newChannel != null)
        {
            OriginateBaseClass.logger.debug("Aborted, Hangup up on the way out");//$NON-NLS-1$
            this.result.setChannelHungup(true);

            PBX pbx = PBXFactory.getActivePBX();
            try
            {
                pbx.hangup(this.newChannel);
            }
            catch (IllegalArgumentException | IllegalStateException | PBXException e)
            {
                logger.error(e, e);

            }
        }
        originateLatch.countDown();

    }

    @Override
    public HashSet<Class< ? extends ManagerEvent>> requiredEvents()
    {
        HashSet<Class< ? extends ManagerEvent>> required = new HashSet<>();

        required.add(OriginateResponseEvent.class);
        required.add(BridgeEvent.class);
        // bridge event is a subclass of linkevent, so we need link & unlink in
        // our list of events to support Asterisk 1.4
        required.add(LinkEvent.class);
        required.add(UnlinkEvent.class);
        required.add(HangupEvent.class);
        required.add(NewChannelEvent.class);

        return required;
    }

    /**
     * It is important that this method is synchronised as there is some
     * interaction between the events and we need to ensure we process one at a
     * time.
     */
    @Override
    synchronized public void onManagerEvent(final ManagerEvent event)
    {
        if (event instanceof HangupEvent)
        {
            final HangupEvent hangupEvt = (HangupEvent) event;
            final Channel hangupChannel = hangupEvt.getChannel();

            if ((this.newChannel != null) && (hangupChannel.isSame(this.newChannel)))
            {
                this.originateSuccess = false;
                OriginateBaseClass.logger.error("Dest channel " + this.newChannel + " hungup after answer"); //$NON-NLS-1$//$NON-NLS-2$
                originateLatch.countDown();
            }
            if ((this.monitorChannel1 != null) && (hangupChannel.isSame(this.monitorChannel1)))
            {
                this.originateSuccess = false;
                this.hungup = true;
                if (this.newChannel != null)
                {
                    OriginateBaseClass.logger.debug("hanging up " + this.newChannel);//$NON-NLS-1$
                    this.result.setChannelHungup(true);

                    PBX pbx = PBXFactory.getActivePBX();
                    try
                    {
                        pbx.hangup(this.newChannel);
                    }
                    catch (IllegalArgumentException | IllegalStateException | PBXException e)
                    {
                        logger.error(e, e);

                    }
                }
                OriginateBaseClass.logger.debug("notify channel 1 hungup");//$NON-NLS-1$
                originateLatch.countDown();
            }
            if ((this.monitorChannel2 != null) && (hangupChannel.isSame(this.monitorChannel2)))
            {
                this.originateSuccess = false;
                this.hungup = true;
                if (this.newChannel != null)
                {
                    OriginateBaseClass.logger.debug("Hanging up channel " + this.newChannel);//$NON-NLS-1$
                    this.result.setChannelHungup(true);

                    PBX pbx = PBXFactory.getActivePBX();
                    try
                    {
                        pbx.hangup(this.newChannel);
                    }
                    catch (IllegalArgumentException | IllegalStateException | PBXException e)
                    {
                        logger.error(e, e);

                    }
                }
                OriginateBaseClass.logger.debug("Notify channel 2 (" + this.monitorChannel2 + ") hungup");//$NON-NLS-1$//$NON-NLS-2$
                originateLatch.countDown();
            }

        }
        if (event instanceof OriginateResponseEvent)
        {
            OriginateBaseClass.logger.debug("response : " + this.newChannel); //$NON-NLS-1$

            final OriginateResponseEvent response = (OriginateResponseEvent) event;
            OriginateBaseClass.logger.debug("OriginateResponseEvent: channel=" //$NON-NLS-1$
                    + (response.isChannel() ? response.getChannel() : response.getEndPoint()) + " originateID:" //$NON-NLS-1$
                    + this.originateID);
            OriginateBaseClass.logger.debug("{" + response.getReason() + ":" + response.getResponse() + "}"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            if (this.originateID != null)
            {
                if (this.originateID.compareToIgnoreCase(response.getActionId()) == 0)
                {
                    this.originateSuccess = response.isSuccess();
                    OriginateBaseClass.logger.debug("OriginateResponse: matched actionId, success=" + this.originateSuccess //$NON-NLS-1$
                            + " channelSeen=" + this.channelSeen); //$NON-NLS-1$

                    this.originateSeen = true;

                    // if we have also seen the channel then we can notify
                    // the
                    // originate() method
                    // that the call is up. Otherwise we will rely on the
                    // NewChannelEvent doing the
                    // notify.
                    if (this.channelSeen == true)
                    {
                        OriginateBaseClass.logger.debug("notify originate response event 305 " + this.originateSuccess);//$NON-NLS-1$
                        originateLatch.countDown();
                    }
                }
            }
            else
            {
                OriginateBaseClass.logger.warn("actionid is null");//$NON-NLS-1$
            }
        }

        // Look for the channel events that tell us that both sides of the
        // call
        // are up.
        // We will see a number of channels come up as the call progresses.
        // The LOCAL/ channels are just internal workings of Asterisk so we
        // need
        // to ignore these.
        if (event instanceof NewChannelEvent)
        {
            final NewChannelEvent newState = (NewChannelEvent) event;
            final Channel channel = newState.getChannel();
            final GetVarAction var = new GetVarAction(channel, OriginateBaseClass.NJR_ORIGINATE_ID);

            OriginateBaseClass.logger.debug("new channel event :" + channel + " context = " + newState.getContext() //$NON-NLS-1$//$NON-NLS-2$
                    + " state =" + newState.getChannelStateDesc() + " state =" + newState.getChannelState()); //$NON-NLS-1$ //$NON-NLS-2$

            // Now try to get the NJR_ORIGINATE_ID's value to see if this is
            // an
            // event for our channel
            // If it is for our channel then the NJR_ORIGINATE_ID will match
            // our
            // originateID.
            // We need to try several times as it can take some time to
            // appear
            // within asterisk.
            int ctr = 0;
            String __originateID = null;
            while ((ctr < 5) && (__originateID == null))
            {
                try
                {
                    ctr++;
                    /*
                     * wait 100ms to allow asterisk time to make the channel
                     * variables available. If you request the channel variables
                     * too soon asterisk responds with channel not found.
                     */
                    Thread.sleep(100);
                    AsteriskPBX pbx = (AsteriskPBX) PBXFactory.getActivePBX();
                    final ManagerResponse response = pbx.sendAction(var, 500);
                    __originateID = response.getAttribute("value"); //$NON-NLS-1$

                    if ((__originateID != null))
                    {
                        // Check if the event is for our channel by checking
                        // the
                        // originateIDs match.
                        if (__originateID.compareToIgnoreCase(this.originateID) == 0)
                        {
                            if ((this.newChannel == null) && !channel.isLocal())
                            {
                                this.newChannel = channel;
                                this.channelSeen = true;

                                OriginateBaseClass.logger.debug("new channel name " + channel); //$NON-NLS-1$ }
                                if (this.listener != null)
                                {
                                    /*
                                     * sometimes it's not actually the NJR phone
                                     * we're originating. Otherwise update the
                                     * NJR phone channel to allow the call to be
                                     * cancelled before it's answered.
                                     */
                                    this.listener.channelUpdate(channel);
                                }

                                if (this.originateSeen == true)
                                {
                                    OriginateBaseClass.logger.debug("notifying success 362");//$NON-NLS-1$
                                    originateLatch.countDown();
                                }
                            }
                        }
                        else
                        {
                            // So we got an originate but it wasn't for us which
                            // means this channel isn't ours.
                            // so we can ignore the event.
                            OriginateBaseClass.logger.debug("originateID " + __originateID); //$NON-NLS-1$
                        }
                    }
                }
                catch (final Exception e)
                {
                    // We only care about error if we are on the last
                    // attempt.
                    if ((this.originateSuccess == false) && (ctr == 4))
                    {
                        OriginateBaseClass.logger.error(e, e);
                    }
                }
            }
        }

        // Look for the channel events that tell us that both sides of the
        // call
        // are up.
        // We will see a number of channels come up as the call progresses.
        // The LOCAL/ channels are just internal workings of Asterisk so we
        // need
        // to ignore these.
        if (event instanceof BridgeEvent)
        {
            final BridgeEvent bridgeEvent = (BridgeEvent) event;
            Channel channel = bridgeEvent.getChannel1();
            if (bridgeEvent.getChannel1().isLocal())
            {
                channel = bridgeEvent.getChannel2();
            }
            final GetVarAction var = new GetVarAction(channel, OriginateBaseClass.NJR_ORIGINATE_ID);

            OriginateBaseClass.logger.debug("new channel event :" + channel + " channel1 = " + bridgeEvent.getChannel1() //$NON-NLS-1$//$NON-NLS-2$
                    + " channel2 =" + bridgeEvent.getChannel2()); //$NON-NLS-1$

            // Now try to get the NJR_ORIGINATE_ID's value to see if this is
            // an
            // event for our channel
            // If it is for our channel then the NJR_ORIGINATE_ID will match
            // our
            // originateID.
            // We need to try several times as it can take some time to
            // appear
            // within asterisk.
            int ctr = 0;
            String __originateID = null;
            while ((ctr < 5) && (__originateID == null))
            {
                try
                {
                    ctr++;
                    /*
                     * wait 100ms to allow asterisk time to make the channel
                     * variables available. If you request the channel variables
                     * too soon asterisk responds with channel not found.
                     */
                    Thread.sleep(100);
                    AsteriskPBX pbx = (AsteriskPBX) PBXFactory.getActivePBX();
                    final ManagerResponse response = pbx.sendAction(var, 500);
                    __originateID = response.getAttribute("value"); //$NON-NLS-1$

                    if ((__originateID != null))
                    {
                        // Check if the event is for our channel by checking
                        // the
                        // originateIDs match.
                        if ((this.originateID != null) && (__originateID.compareToIgnoreCase(this.originateID) == 0))
                        {
                            if ((this.newChannel == null) && !channel.isLocal())
                            {
                                this.newChannel = channel;
                                this.channelSeen = true;

                                OriginateBaseClass.logger.debug("new channel name " + channel); //$NON-NLS-1$ }
                                if (this.listener != null)
                                {
                                    /*
                                     * sometimes it's not actually the NJR phone
                                     * we're originating. Otherwise update the
                                     * NJR phone channel to allow the call to be
                                     * cancelled before it's answered.
                                     */
                                    this.listener.channelUpdate(channel);
                                }

                                if (this.originateSeen == true)
                                {
                                    OriginateBaseClass.logger.debug("notifying success 362");//$NON-NLS-1$
                                    originateLatch.countDown();
                                }
                            }
                        }
                    }
                    else
                    {
                        // So we got an originate but it wasn't for us which
                        // means this channel isn't ours.
                        // so we can ignore the event.
                        OriginateBaseClass.logger.debug("originateID " + __originateID); //$NON-NLS-1$
                    }
                }
                catch (final Exception e)
                {
                    // We only care about error if we are on the last
                    // attempt.
                    if ((this.originateSuccess == false) && (ctr == 4))
                    {
                        OriginateBaseClass.logger.error(e, e);
                    }
                }
            }
        }

    }

}
