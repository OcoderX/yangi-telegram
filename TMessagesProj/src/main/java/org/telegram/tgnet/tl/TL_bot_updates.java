package org.telegram.tgnet.tl;

import org.telegram.tgnet.InputSerializedData;
import org.telegram.tgnet.OutputSerializedData;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.Vector;

import java.util.ArrayList;

/** Typed bot-only updates; one unsupported member must not invalidate a message difference. */
public final class TL_bot_updates {
    private TL_bot_updates() {}

    public abstract static class BotQtsUpdate extends TLRPC.Update {
        public int qts;
    }

    public abstract static class BotInlineMessageID extends TLObject {
        public static BotInlineMessageID TLdeserialize(InputSerializedData stream, int constructor, boolean exception) {
            BotInlineMessageID result = null;
            if (constructor == TL_inputBotInlineMessageID.constructor) result = new TL_inputBotInlineMessageID();
            else if (constructor == TL_inputBotInlineMessageID64.constructor) result = new TL_inputBotInlineMessageID64();
            return TLdeserialize(BotInlineMessageID.class, result, stream, constructor, exception);
        }
    }

    public static TLRPC.Update fromConstructor(int constructor) {
        switch (constructor) {
            case TL_updateBotStopped.constructor: return new TL_updateBotStopped();
            case TL_updateBotInlineQuery.constructor: return new TL_updateBotInlineQuery();
            case TL_updateBotInlineSend.constructor: return new TL_updateBotInlineSend();
            case TL_updateBotCallbackQuery.constructor: return new TL_updateBotCallbackQuery();
            case TL_updateInlineBotCallbackQuery.constructor: return new TL_updateInlineBotCallbackQuery();
            case TL_updateBotWebhookJSON.constructor: return new TL_updateBotWebhookJSON();
            case TL_updateBotWebhookJSONQuery.constructor: return new TL_updateBotWebhookJSONQuery();
            case TL_updateBotShippingQuery.constructor: return new TL_updateBotShippingQuery();
            case TL_updateBotPrecheckoutQuery.constructor: return new TL_updateBotPrecheckoutQuery();
            case TL_updateChatParticipant.constructor: return new TL_updateChatParticipant();
            case TL_updateBotChatInviteRequester.constructor: return new TL_updateBotChatInviteRequester();
            case TL_updateBotChatBoost.constructor: return new TL_updateBotChatBoost();
            case TL_updateBotMessageReaction.constructor: return new TL_updateBotMessageReaction();
            case TL_updateBotMessageReactions.constructor: return new TL_updateBotMessageReactions();
            case TL_updateBotBusinessConnect.constructor: return new TL_updateBotBusinessConnect();
            case TL_updateBotNewBusinessMessage.constructor: return new TL_updateBotNewBusinessMessage();
            case TL_updateBotEditBusinessMessage.constructor: return new TL_updateBotEditBusinessMessage();
            case TL_updateBotDeleteBusinessMessage.constructor: return new TL_updateBotDeleteBusinessMessage();
            case TL_updateBusinessBotCallbackQuery.constructor: return new TL_updateBusinessBotCallbackQuery();
            case TL_updateBotGuestChatQuery.constructor: return new TL_updateBotGuestChatQuery();
            default: return null;
        }
    }

    public static class TL_updateBotStopped extends BotQtsUpdate {
        public static final int constructor = 0xc4870a49;
        public long user_id;
        public int date;
        public boolean stopped;

        @Override
        public void readParams(InputSerializedData stream, boolean exception) {
            user_id = stream.readInt64(exception);
            date = stream.readInt32(exception);
            stopped = stream.readBool(exception);
            qts = stream.readInt32(exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt64(user_id);
            stream.writeInt32(date);
            stream.writeBool(stopped);
            stream.writeInt32(qts);
        }
    }

    public static class TL_updateBotInlineQuery extends TLRPC.Update {
        public static final int constructor = 0x496f379c;
        public int flags;
        public long query_id;
        public long user_id;
        public String query;
        public TLRPC.GeoPoint geo;
        public TLRPC.InlineQueryPeerType peer_type;
        public String offset;

        @Override
        public void readParams(InputSerializedData stream, boolean exception) {
            flags = stream.readInt32(exception);
            query_id = stream.readInt64(exception);
            user_id = stream.readInt64(exception);
            query = stream.readString(exception);
            if (hasFlag(flags, 1 << 0)) geo = TLRPC.GeoPoint.TLdeserialize(stream, stream.readInt32(exception), exception);
            if (hasFlag(flags, 1 << 1)) peer_type = TLRPC.InlineQueryPeerType.TLdeserialize(stream, stream.readInt32(exception), exception);
            offset = stream.readString(exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt32(flags);
            stream.writeInt64(query_id);
            stream.writeInt64(user_id);
            stream.writeString(query);
            if (hasFlag(flags, 1 << 0)) geo.serializeToStream(stream);
            if (hasFlag(flags, 1 << 1)) peer_type.serializeToStream(stream);
            stream.writeString(offset);
        }
    }

    public static class TL_updateBotInlineSend extends TLRPC.Update {
        public static final int constructor = 0x12f12a07;
        public int flags;
        public long user_id;
        public String query;
        public TLRPC.GeoPoint geo;
        public String id;
        public BotInlineMessageID msg_id;

        @Override
        public void readParams(InputSerializedData stream, boolean exception) {
            flags = stream.readInt32(exception);
            user_id = stream.readInt64(exception);
            query = stream.readString(exception);
            if (hasFlag(flags, 1 << 0)) geo = TLRPC.GeoPoint.TLdeserialize(stream, stream.readInt32(exception), exception);
            id = stream.readString(exception);
            if (hasFlag(flags, 1 << 1)) msg_id = BotInlineMessageID.TLdeserialize(stream, stream.readInt32(exception), exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt32(flags);
            stream.writeInt64(user_id);
            stream.writeString(query);
            if (hasFlag(flags, 1 << 0)) geo.serializeToStream(stream);
            stream.writeString(id);
            if (hasFlag(flags, 1 << 1)) msg_id.serializeToStream(stream);
        }
    }

    public static class TL_updateBotCallbackQuery extends TLRPC.Update {
        public static final int constructor = 0xb9cfc48d;
        public int flags;
        public long query_id;
        public long user_id;
        public TLRPC.Peer peer;
        public int msg_id;
        public long chat_instance;
        public byte[] data;
        public String game_short_name;

        @Override
        public void readParams(InputSerializedData stream, boolean exception) {
            flags = stream.readInt32(exception);
            query_id = stream.readInt64(exception);
            user_id = stream.readInt64(exception);
            peer = TLRPC.Peer.TLdeserialize(stream, stream.readInt32(exception), exception);
            msg_id = stream.readInt32(exception);
            chat_instance = stream.readInt64(exception);
            if (hasFlag(flags, 1 << 0)) data = stream.readByteArray(exception);
            if (hasFlag(flags, 1 << 1)) game_short_name = stream.readString(exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt32(flags);
            stream.writeInt64(query_id);
            stream.writeInt64(user_id);
            peer.serializeToStream(stream);
            stream.writeInt32(msg_id);
            stream.writeInt64(chat_instance);
            if (hasFlag(flags, 1 << 0)) stream.writeByteArray(data);
            if (hasFlag(flags, 1 << 1)) stream.writeString(game_short_name);
        }
    }

    public static class TL_updateInlineBotCallbackQuery extends TLRPC.Update {
        public static final int constructor = 0x691e9052;
        public int flags;
        public long query_id;
        public long user_id;
        public BotInlineMessageID msg_id;
        public long chat_instance;
        public byte[] data;
        public String game_short_name;

        @Override
        public void readParams(InputSerializedData stream, boolean exception) {
            flags = stream.readInt32(exception);
            query_id = stream.readInt64(exception);
            user_id = stream.readInt64(exception);
            msg_id = BotInlineMessageID.TLdeserialize(stream, stream.readInt32(exception), exception);
            chat_instance = stream.readInt64(exception);
            if (hasFlag(flags, 1 << 0)) data = stream.readByteArray(exception);
            if (hasFlag(flags, 1 << 1)) game_short_name = stream.readString(exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt32(flags);
            stream.writeInt64(query_id);
            stream.writeInt64(user_id);
            msg_id.serializeToStream(stream);
            stream.writeInt64(chat_instance);
            if (hasFlag(flags, 1 << 0)) stream.writeByteArray(data);
            if (hasFlag(flags, 1 << 1)) stream.writeString(game_short_name);
        }
    }

    public static class TL_updateBotWebhookJSON extends TLRPC.Update {
        public static final int constructor = 0x8317c0c3;
        public TLRPC.TL_dataJSON data;

        @Override
        public void readParams(InputSerializedData stream, boolean exception) {
            data = TLRPC.TL_dataJSON.TLdeserialize(stream, stream.readInt32(exception), exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            data.serializeToStream(stream);
        }
    }

    public static class TL_updateBotWebhookJSONQuery extends TLRPC.Update {
        public static final int constructor = 0x9b9240a6;
        public long query_id;
        public TLRPC.TL_dataJSON data;
        public int timeout;

        @Override
        public void readParams(InputSerializedData stream, boolean exception) {
            query_id = stream.readInt64(exception);
            data = TLRPC.TL_dataJSON.TLdeserialize(stream, stream.readInt32(exception), exception);
            timeout = stream.readInt32(exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt64(query_id);
            data.serializeToStream(stream);
            stream.writeInt32(timeout);
        }
    }

    public static class TL_updateBotShippingQuery extends TLRPC.Update {
        public static final int constructor = 0xb5aefd7d;
        public long query_id;
        public long user_id;
        public byte[] payload;
        public TLRPC.TL_postAddress shipping_address;

        @Override
        public void readParams(InputSerializedData stream, boolean exception) {
            query_id = stream.readInt64(exception);
            user_id = stream.readInt64(exception);
            payload = stream.readByteArray(exception);
            shipping_address = TLRPC.TL_postAddress.TLdeserialize(stream, stream.readInt32(exception), exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt64(query_id);
            stream.writeInt64(user_id);
            stream.writeByteArray(payload);
            shipping_address.serializeToStream(stream);
        }
    }

    public static class TL_updateBotPrecheckoutQuery extends TLRPC.Update {
        public static final int constructor = 0x8caa9a96;
        public int flags;
        public long query_id;
        public long user_id;
        public byte[] payload;
        public TLRPC.TL_paymentRequestedInfo info;
        public String shipping_option_id;
        public String currency;
        public long total_amount;

        @Override
        public void readParams(InputSerializedData stream, boolean exception) {
            flags = stream.readInt32(exception);
            query_id = stream.readInt64(exception);
            user_id = stream.readInt64(exception);
            payload = stream.readByteArray(exception);
            if (hasFlag(flags, 1 << 0)) info = TLRPC.TL_paymentRequestedInfo.TLdeserialize(stream, stream.readInt32(exception), exception);
            if (hasFlag(flags, 1 << 1)) shipping_option_id = stream.readString(exception);
            currency = stream.readString(exception);
            total_amount = stream.readInt64(exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt32(flags);
            stream.writeInt64(query_id);
            stream.writeInt64(user_id);
            stream.writeByteArray(payload);
            if (hasFlag(flags, 1 << 0)) info.serializeToStream(stream);
            if (hasFlag(flags, 1 << 1)) stream.writeString(shipping_option_id);
            stream.writeString(currency);
            stream.writeInt64(total_amount);
        }
    }

    public static class TL_updateChatParticipant extends BotQtsUpdate {
        public static final int constructor = 0xd087663a;
        public int flags;
        public long chat_id;
        public int date;
        public long actor_id;
        public long user_id;
        public TLRPC.ChatParticipant prev_participant;
        public TLRPC.ChatParticipant new_participant;
        public TLRPC.ExportedChatInvite invite;

        @Override
        public void readParams(InputSerializedData stream, boolean exception) {
            flags = stream.readInt32(exception);
            chat_id = stream.readInt64(exception);
            date = stream.readInt32(exception);
            actor_id = stream.readInt64(exception);
            user_id = stream.readInt64(exception);
            if (hasFlag(flags, 1 << 0)) prev_participant = TLRPC.ChatParticipant.TLdeserialize(stream, stream.readInt32(exception), exception);
            if (hasFlag(flags, 1 << 1)) new_participant = TLRPC.ChatParticipant.TLdeserialize(stream, stream.readInt32(exception), exception);
            if (hasFlag(flags, 1 << 2)) invite = TLRPC.ExportedChatInvite.TLdeserialize(stream, stream.readInt32(exception), exception);
            qts = stream.readInt32(exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt32(flags);
            stream.writeInt64(chat_id);
            stream.writeInt32(date);
            stream.writeInt64(actor_id);
            stream.writeInt64(user_id);
            if (hasFlag(flags, 1 << 0)) prev_participant.serializeToStream(stream);
            if (hasFlag(flags, 1 << 1)) new_participant.serializeToStream(stream);
            if (hasFlag(flags, 1 << 2)) invite.serializeToStream(stream);
            stream.writeInt32(qts);
        }
    }

    public static class TL_updateBotChatInviteRequester extends BotQtsUpdate {
        public static final int constructor = 0x7cb34d79;
        public int flags;
        public TLRPC.Peer peer;
        public int date;
        public long user_id;
        public String about;
        public TLRPC.ExportedChatInvite invite;
        public long query_id;

        @Override
        public void readParams(InputSerializedData stream, boolean exception) {
            flags = stream.readInt32(exception);
            peer = TLRPC.Peer.TLdeserialize(stream, stream.readInt32(exception), exception);
            date = stream.readInt32(exception);
            user_id = stream.readInt64(exception);
            about = stream.readString(exception);
            invite = TLRPC.ExportedChatInvite.TLdeserialize(stream, stream.readInt32(exception), exception);
            qts = stream.readInt32(exception);
            if (hasFlag(flags, 1 << 0)) query_id = stream.readInt64(exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt32(flags);
            peer.serializeToStream(stream);
            stream.writeInt32(date);
            stream.writeInt64(user_id);
            stream.writeString(about);
            invite.serializeToStream(stream);
            stream.writeInt32(qts);
            if (hasFlag(flags, 1 << 0)) stream.writeInt64(query_id);
        }
    }

    public static class TL_updateBotChatBoost extends BotQtsUpdate {
        public static final int constructor = 0x904dd49c;
        public TLRPC.Peer peer;
        public TL_stories.Boost boost;

        @Override
        public void readParams(InputSerializedData stream, boolean exception) {
            peer = TLRPC.Peer.TLdeserialize(stream, stream.readInt32(exception), exception);
            boost = TL_stories.Boost.TLdeserialize(stream, stream.readInt32(exception), exception);
            qts = stream.readInt32(exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            peer.serializeToStream(stream);
            boost.serializeToStream(stream);
            stream.writeInt32(qts);
        }
    }

    public static class TL_updateBotMessageReaction extends BotQtsUpdate {
        public static final int constructor = 0xac21d3ce;
        public TLRPC.Peer peer;
        public int msg_id;
        public int date;
        public TLRPC.Peer actor;
        public ArrayList<TLRPC.Reaction> old_reactions = new ArrayList<>();
        public ArrayList<TLRPC.Reaction> new_reactions = new ArrayList<>();

        @Override
        public void readParams(InputSerializedData stream, boolean exception) {
            peer = TLRPC.Peer.TLdeserialize(stream, stream.readInt32(exception), exception);
            msg_id = stream.readInt32(exception);
            date = stream.readInt32(exception);
            actor = TLRPC.Peer.TLdeserialize(stream, stream.readInt32(exception), exception);
            old_reactions = Vector.deserialize(stream, TLRPC.Reaction::TLdeserialize, exception);
            new_reactions = Vector.deserialize(stream, TLRPC.Reaction::TLdeserialize, exception);
            qts = stream.readInt32(exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            peer.serializeToStream(stream);
            stream.writeInt32(msg_id);
            stream.writeInt32(date);
            actor.serializeToStream(stream);
            Vector.serialize(stream, old_reactions);
            Vector.serialize(stream, new_reactions);
            stream.writeInt32(qts);
        }
    }

    public static class TL_updateBotMessageReactions extends BotQtsUpdate {
        public static final int constructor = 0x9cb7759;
        public TLRPC.Peer peer;
        public int msg_id;
        public int date;
        public ArrayList<TLRPC.ReactionCount> reactions = new ArrayList<>();

        @Override
        public void readParams(InputSerializedData stream, boolean exception) {
            peer = TLRPC.Peer.TLdeserialize(stream, stream.readInt32(exception), exception);
            msg_id = stream.readInt32(exception);
            date = stream.readInt32(exception);
            reactions = Vector.deserialize(stream, TLRPC.ReactionCount::TLdeserialize, exception);
            qts = stream.readInt32(exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            peer.serializeToStream(stream);
            stream.writeInt32(msg_id);
            stream.writeInt32(date);
            Vector.serialize(stream, reactions);
            stream.writeInt32(qts);
        }
    }

    public static class TL_updateBotBusinessConnect extends BotQtsUpdate {
        public static final int constructor = 0x8ae5c97a;
        public TL_botBusinessConnection connection;

        @Override
        public void readParams(InputSerializedData stream, boolean exception) {
            connection = TL_botBusinessConnection.TLdeserialize(stream, stream.readInt32(exception), exception);
            qts = stream.readInt32(exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            connection.serializeToStream(stream);
            stream.writeInt32(qts);
        }
    }

    public static class TL_updateBotNewBusinessMessage extends BotQtsUpdate {
        public static final int constructor = 0x9ddb347c;
        public int flags;
        public String connection_id;
        public TLRPC.Message message;
        public TLRPC.Message reply_to_message;

        @Override
        public void readParams(InputSerializedData stream, boolean exception) {
            flags = stream.readInt32(exception);
            connection_id = stream.readString(exception);
            message = TLRPC.Message.TLdeserialize(stream, stream.readInt32(exception), exception);
            if (hasFlag(flags, 1 << 0)) reply_to_message = TLRPC.Message.TLdeserialize(stream, stream.readInt32(exception), exception);
            qts = stream.readInt32(exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt32(flags);
            stream.writeString(connection_id);
            message.serializeToStream(stream);
            if (hasFlag(flags, 1 << 0)) reply_to_message.serializeToStream(stream);
            stream.writeInt32(qts);
        }
    }

    public static class TL_updateBotEditBusinessMessage extends BotQtsUpdate {
        public static final int constructor = 0x7df587c;
        public int flags;
        public String connection_id;
        public TLRPC.Message message;
        public TLRPC.Message reply_to_message;

        @Override
        public void readParams(InputSerializedData stream, boolean exception) {
            flags = stream.readInt32(exception);
            connection_id = stream.readString(exception);
            message = TLRPC.Message.TLdeserialize(stream, stream.readInt32(exception), exception);
            if (hasFlag(flags, 1 << 0)) reply_to_message = TLRPC.Message.TLdeserialize(stream, stream.readInt32(exception), exception);
            qts = stream.readInt32(exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt32(flags);
            stream.writeString(connection_id);
            message.serializeToStream(stream);
            if (hasFlag(flags, 1 << 0)) reply_to_message.serializeToStream(stream);
            stream.writeInt32(qts);
        }
    }

    public static class TL_updateBotDeleteBusinessMessage extends BotQtsUpdate {
        public static final int constructor = 0xa02a982e;
        public String connection_id;
        public TLRPC.Peer peer;
        public ArrayList<Integer> messages = new ArrayList<>();

        @Override
        public void readParams(InputSerializedData stream, boolean exception) {
            connection_id = stream.readString(exception);
            peer = TLRPC.Peer.TLdeserialize(stream, stream.readInt32(exception), exception);
            messages = Vector.deserializeInt(stream, exception);
            qts = stream.readInt32(exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeString(connection_id);
            peer.serializeToStream(stream);
            Vector.serializeInt(stream, messages);
            stream.writeInt32(qts);
        }
    }

    public static class TL_updateBusinessBotCallbackQuery extends TLRPC.Update {
        public static final int constructor = 0x1ea2fda7;
        public int flags;
        public long query_id;
        public long user_id;
        public String connection_id;
        public TLRPC.Message message;
        public TLRPC.Message reply_to_message;
        public long chat_instance;
        public byte[] data;

        @Override
        public void readParams(InputSerializedData stream, boolean exception) {
            flags = stream.readInt32(exception);
            query_id = stream.readInt64(exception);
            user_id = stream.readInt64(exception);
            connection_id = stream.readString(exception);
            message = TLRPC.Message.TLdeserialize(stream, stream.readInt32(exception), exception);
            if (hasFlag(flags, 1 << 2)) reply_to_message = TLRPC.Message.TLdeserialize(stream, stream.readInt32(exception), exception);
            chat_instance = stream.readInt64(exception);
            if (hasFlag(flags, 1 << 0)) data = stream.readByteArray(exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt32(flags);
            stream.writeInt64(query_id);
            stream.writeInt64(user_id);
            stream.writeString(connection_id);
            message.serializeToStream(stream);
            if (hasFlag(flags, 1 << 2)) reply_to_message.serializeToStream(stream);
            stream.writeInt64(chat_instance);
            if (hasFlag(flags, 1 << 0)) stream.writeByteArray(data);
        }
    }

    public static class TL_updateBotGuestChatQuery extends BotQtsUpdate {
        public static final int constructor = 0xcdd4093d;
        public int flags;
        public long query_id;
        public TLRPC.Message message;
        public ArrayList<TLRPC.Message> reference_messages = new ArrayList<>();

        @Override
        public void readParams(InputSerializedData stream, boolean exception) {
            flags = stream.readInt32(exception);
            query_id = stream.readInt64(exception);
            message = TLRPC.Message.TLdeserialize(stream, stream.readInt32(exception), exception);
            if (hasFlag(flags, 1 << 0)) reference_messages = Vector.deserialize(stream, TLRPC.Message::TLdeserialize, exception);
            qts = stream.readInt32(exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt32(flags);
            stream.writeInt64(query_id);
            message.serializeToStream(stream);
            if (hasFlag(flags, 1 << 0)) Vector.serialize(stream, reference_messages);
            stream.writeInt32(qts);
        }
    }

    public static class TL_botBusinessConnection extends TLObject {
        public static final int constructor = 0x8f34b2f5;
        public int flags;
        public boolean disabled;
        public String connection_id;
        public long user_id;
        public int dc_id;
        public int date;
        public TL_account.TL_businessBotRights rights;

        public static TL_botBusinessConnection TLdeserialize(InputSerializedData stream, int constructor, boolean exception) {
            return TLdeserialize(TL_botBusinessConnection.class, constructor == TL_botBusinessConnection.constructor ? new TL_botBusinessConnection() : null, stream, constructor, exception);
        }

        @Override
        public void readParams(InputSerializedData stream, boolean exception) {
            flags = stream.readInt32(exception);
            disabled = hasFlag(flags, 1 << 1);
            connection_id = stream.readString(exception);
            user_id = stream.readInt64(exception);
            dc_id = stream.readInt32(exception);
            date = stream.readInt32(exception);
            if (hasFlag(flags, 1 << 2)) rights = TL_account.TL_businessBotRights.TLdeserialize(stream, stream.readInt32(exception), exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            flags = setFlag(flags, 1 << 1, disabled);
            stream.writeInt32(flags);
            stream.writeString(connection_id);
            stream.writeInt64(user_id);
            stream.writeInt32(dc_id);
            stream.writeInt32(date);
            if (hasFlag(flags, 1 << 2)) rights.serializeToStream(stream);
        }
    }

    public static class TL_inputBotInlineMessageID extends BotInlineMessageID {
        public static final int constructor = 0x890c3d89;
        public int dc_id;
        public long id;
        public long access_hash;

        @Override
        public void readParams(InputSerializedData stream, boolean exception) {
            dc_id = stream.readInt32(exception);
            id = stream.readInt64(exception);
            access_hash = stream.readInt64(exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt32(dc_id);
            stream.writeInt64(id);
            stream.writeInt64(access_hash);
        }
    }

    public static class TL_inputBotInlineMessageID64 extends BotInlineMessageID {
        public static final int constructor = 0xb6d915d7;
        public int dc_id;
        public long owner_id;
        public int id;
        public long access_hash;

        @Override
        public void readParams(InputSerializedData stream, boolean exception) {
            dc_id = stream.readInt32(exception);
            owner_id = stream.readInt64(exception);
            id = stream.readInt32(exception);
            access_hash = stream.readInt64(exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt32(dc_id);
            stream.writeInt64(owner_id);
            stream.writeInt32(id);
            stream.writeInt64(access_hash);
        }
    }
}
