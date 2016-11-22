package org.asteriskjava.pbx.internal.activity;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;

import org.apache.log4j.Logger;
import org.asteriskjava.pbx.ActivityCallback;
import org.asteriskjava.pbx.AsteriskSettings;
import org.asteriskjava.pbx.Call;
import org.asteriskjava.pbx.CallImpl;
import org.asteriskjava.pbx.Channel;
import org.asteriskjava.pbx.EndPoint;
import org.asteriskjava.pbx.ListenerPriority;
import org.asteriskjava.pbx.PBXException;
import org.asteriskjava.pbx.PBXFactory;
import org.asteriskjava.pbx.activities.SplitActivity;
import org.asteriskjava.pbx.agi.AgiChannelActivityHold;
import org.asteriskjava.pbx.asterisk.wrap.actions.RedirectAction;
import org.asteriskjava.pbx.asterisk.wrap.events.ManagerEvent;
import org.asteriskjava.pbx.internal.core.AsteriskPBX;
import org.asteriskjava.pbx.internal.core.ChannelProxy;

/**
 * The SplitActivity is used by the AsteriksPBX to split a call and place the
 * component channels into the specialist Activity fastagi. The SplitActivity
 * can only split a call with two channels. The (obvious?) limitation is that we
 * can't split something in a conference call as it has more than two channels.
 * 
 * @author bsutton
 */
public class SplitActivityImpl extends ActivityHelper<SplitActivity> implements SplitActivity
{
    static Logger logger = Logger.getLogger(SplitActivityImpl.class);

    private Call _callToSplit;

    private Channel channel1;

    private Channel channel2;

    private Call _lhsCall;

    private Call _rhsCall;

    /**
     * Splits a call by moving each of its two channels into the Activity agi.
     * The channels will sit in the agi (with no audio) until something is done with them.
     * As such you should leave them split for too long.
     * 
     * 
     * @param callToSplit The call to split
     * @param listener
     */
    public SplitActivityImpl(final Call callToSplit, final ActivityCallback<SplitActivity> listener)
    {
        super("SplitActivity", listener); //$NON-NLS-1$

        this._callToSplit = callToSplit;

        channel1 = callToSplit.getChannels().get(0);
        channel2 = callToSplit.getChannels().get(1);

        callToSplit.getChannels();

        this.startActivity(true);
    }

    @Override
    public boolean doActivity() throws PBXException
    {

        SplitActivityImpl.logger.info("*******************************************************************************"); //$NON-NLS-1$
        SplitActivityImpl.logger.info("***********                    begin split               ****************"); //$NON-NLS-1$
        SplitActivityImpl.logger.info("***********            " + this.channel1 + "                 ****************"); //$NON-NLS-1$ //$NON-NLS-2$
        SplitActivityImpl.logger.info("***********            " + this.channel2 + "                 ****************"); //$NON-NLS-1$ //$NON-NLS-2$
        SplitActivityImpl.logger.info("*******************************************************************************"); //$NON-NLS-1$

        // Splits the originating and secondary channels by moving each of them
        // into the associated
        // target.
        boolean success = false;

        if (this.channel2 != null)
        {
            success = splitTwo();

            // Now update the call to reflect the split
            if (success)
            {
                this._lhsCall = ((CallImpl) this._callToSplit).split(channel1);
                this._rhsCall = ((CallImpl) this._callToSplit).split(channel2);
            }
        }
        return success;
    }

    @Override
    public HashSet<Class< ? extends ManagerEvent>> requiredEvents()
    {
        HashSet<Class< ? extends ManagerEvent>> required = new HashSet<>();

        // No events required.
        return required;
    }

    @Override
    synchronized public void onManagerEvent(final ManagerEvent event)
    {
        // NOOP
    }

    @Override
    public ListenerPriority getPriority()
    {
        return ListenerPriority.NORMAL;
    }

    /**
     * After a call has been split we get two new calls. One will hold the
     * original remote party and the other will hold the original local party.
     * 
     * @return the call which holds the original remote party.
     */
    @Override
    public Call getLHSCall()
    {
        return this._lhsCall;
    }

    /**
     * After a call has been split we get two new calls. One will hold the
     * original remote party and the other will hold the original local party.
     * 
     * @return the call which holds the original local party.
     */
    @Override
    public Call getRHSCall()
    {
        return this._rhsCall;
    }

    /**
     * Splits two channels moving them to defined endpoints.
     * 
     * @param lhs
     * @param lhsTarget
     * @param lhsTargetContext
     * @param rhs
     * @param rhsTarget
     * @param rhsTargetContext
     * @return
     * @throws PBXException
     */
    private boolean splitTwo() throws PBXException
    {
        final AsteriskSettings profile = PBXFactory.getActiveProfile();
        AsteriskPBX pbx = (AsteriskPBX) PBXFactory.getActivePBX();

        if (channel1 == channel2)
        {
            throw new NullPointerException(
                    "channel1 is the same as channel2. if I let this happen, asterisk will core dump :)");
        }

        List<Channel> channels = new LinkedList<>();
        channels.add(channel1);
        channels.add(channel2);
        if (!pbx.waitForChannelsToQuiescent(channels, 3000))
        {
            logger.error(callSite, callSite);
            throw new PBXException(
                    "Channel: " + channel1 + " or " + channel2 + " cannot be split as they are still in transition.");
        }

        /*
         * redirects the specified channels to the specified endpoints. Returns
         * true or false reflecting success.
         */

        AgiChannelActivityHold agi1 = new AgiChannelActivityHold();
        AgiChannelActivityHold agi2 = new AgiChannelActivityHold();

        pbx.setVariable(channel1, "proxyId", "" + ((ChannelProxy) channel1).getIdentity());
        pbx.setVariable(channel2, "proxyId", "" + ((ChannelProxy) channel2).getIdentity());

        channel1.setCurrentActivityAction(agi1);
        channel2.setCurrentActivityAction(agi2);

        final String agiExten = profile.getAgiExtension();
        final String agiContext = profile.getManagementContext();
        logger.debug("splitTwo channel lhs:" + channel1 + " to " + agiExten + " in context " + agiContext + " from "
                + this._callToSplit);

        final EndPoint extensionAgi = pbx.getExtensionAgi();
        final RedirectAction redirect = new RedirectAction(channel1, agiContext, extensionAgi, 1);
        redirect.setExtraChannel(channel2);
        redirect.setExtraContext(agiContext);
        redirect.setExtraExten(extensionAgi);
        redirect.setExtraPriority(1);
        // logger.error(redirect);

        boolean ret = false;
        {
            try
            {

                // final ManagerResponse response =
                pbx.sendAction(redirect, 1000);
                double ctr = 0;
                while ((!agi1.hasCallReachedAgi() || !agi2.hasCallReachedAgi()) && ctr < 10)
                {
                    Thread.sleep(100);
                    ctr += 100.0 / 1000.0;
                    if (!agi1.hasCallReachedAgi())
                    {
                        logger.error("Waiting on (agi1) " + channel1);
                    }
                    if (!agi2.hasCallReachedAgi())
                    {
                        logger.error("Waiting on (agi2) " + channel2);
                    }
                }
                ret = agi1.hasCallReachedAgi() && agi2.hasCallReachedAgi();

            }
            catch (final Exception e)
            {
                logger.error(e, e);
            }
        }
        return ret;
    }

}
