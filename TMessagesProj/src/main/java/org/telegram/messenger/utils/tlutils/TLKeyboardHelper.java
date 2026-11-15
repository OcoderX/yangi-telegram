package org.telegram.messenger.utils.tlutils;

import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.tl.TL_iv;
import org.telegram.tgnet.tl.TL_keyboard;

import java.util.ArrayList;

/** Adapts the wire keyboard schema to the existing chat keyboard handlers. */
public final class TLKeyboardHelper {
    private TLKeyboardHelper() {}

    public static <T extends TL_keyboard.ButtonTypeProto> T getType(TL_keyboard.KeyboardButtonProto button, Class<T> typeClass) {
        if (button == null || typeClass == null) return null;
        final TL_keyboard.ButtonTypeProto type = button.getType();
        return typeClass.isInstance(type) ? typeClass.cast(type) : null;
    }

    public static <T extends TL_keyboard.ButtonTypeProto> boolean isType(TL_keyboard.KeyboardButtonProto button, Class<T> typeClass) {
        return getType(button, typeClass) != null;
    }

    public static boolean isButtonWebView(TL_keyboard.KeyboardButtonProto button) {
        return isType(button, TL_keyboard.TL_inlineButtonTypeWebView.class)
            || isType(button, TL_keyboard.TL_buttonTypeSimpleWebView.class);
    }

    public static boolean isDisabled(TL_keyboard.KeyboardButtonProto button) {
        return button == null || button.getType() == null || isType(button, TL_keyboard.TL_inlineButtonTypeDisabled.class);
    }

    public static boolean isForceReply(TLRPC.ReplyMarkup markup) {
        return markup instanceof TLRPC.TL_replyKeyboardForceReply || markup != null && markup.force_reply;
    }

    public static TL_keyboard.RichButtonStyle getStyle(TL_keyboard.KeyboardButtonProto button) {
        if (button instanceof TL_iv.textButton) return ((TL_iv.textButton) button).style;
        if (button instanceof TL_keyboard.PageButton) return ((TL_keyboard.PageButton) button).style;
        return null;
    }

    /** Conversion is cached on the parsed button; rendering never rebuilds it. */
    public static TLRPC.KeyboardButton toLegacyButton(TL_keyboard.KeyboardButtonProto proto) {
        if (proto == null) return null;
        TLRPC.KeyboardButton cached = getCached(proto);
        if (cached != null) return cached;
        final TL_keyboard.ButtonTypeProto type = proto.getType();
        final TLRPC.KeyboardButton button;
        if (type instanceof TL_keyboard.TL_inlineButtonTypeCallback) {
            TL_keyboard.TL_inlineButtonTypeCallback value = (TL_keyboard.TL_inlineButtonTypeCallback) type;
            button = new TLRPC.TL_keyboardButtonCallback();
            button.flags = value.flags;
            button.data = value.data;
            button.requires_password = value.requires_password;
        } else if (type instanceof TL_keyboard.TL_inlineButtonTypeUrl) {
            button = new TLRPC.TL_keyboardButtonUrl();
            button.url = ((TL_keyboard.TL_inlineButtonTypeUrl) type).url;
        } else if (type instanceof TL_keyboard.TL_inlineButtonTypeUrlAuth) {
            TL_keyboard.TL_inlineButtonTypeUrlAuth value = (TL_keyboard.TL_inlineButtonTypeUrlAuth) type;
            button = new TLRPC.TL_keyboardButtonUrlAuth();
            button.flags = value.flags;
            button.url = value.url;
            button.fwd_text = value.fwd_text;
            button.button_id = value.button_id;
        } else if (type instanceof TL_keyboard.TL_inputInlineButtonTypeUrlAuth) {
            TL_keyboard.TL_inputInlineButtonTypeUrlAuth value = (TL_keyboard.TL_inputInlineButtonTypeUrlAuth) type;
            button = new TLRPC.TL_inputKeyboardButtonUrlAuth();
            button.flags = value.flags;
            button.url = value.url;
            button.fwd_text = value.fwd_text;
            button.bot = value.bot;
            button.request_write_access = value.request_write_access;
        } else if (type instanceof TL_keyboard.TL_inlineButtonTypeSwitchInline) {
            TL_keyboard.TL_inlineButtonTypeSwitchInline value = (TL_keyboard.TL_inlineButtonTypeSwitchInline) type;
            button = new TLRPC.TL_keyboardButtonSwitchInline();
            button.flags = value.flags;
            button.query = value.query;
            button.same_peer = value.same_peer;
            if (value.peer_types != null) button.peer_types = value.peer_types;
        } else if (type instanceof TL_keyboard.TL_inlineButtonTypeGame) {
            button = new TLRPC.TL_keyboardButtonGame();
        } else if (type instanceof TL_keyboard.TL_inlineButtonTypeBuy) {
            button = new TLRPC.TL_keyboardButtonBuy();
        } else if (type instanceof TL_keyboard.TL_inlineButtonTypeWebView) {
            button = new TLRPC.TL_keyboardButtonWebView();
            button.url = ((TL_keyboard.TL_inlineButtonTypeWebView) type).url;
        } else if (type instanceof TL_keyboard.TL_inlineButtonTypeCopy) {
            TLRPC.TL_keyboardButtonCopy value = new TLRPC.TL_keyboardButtonCopy();
            value.copy_text = ((TL_keyboard.TL_inlineButtonTypeCopy) type).copy_text;
            button = value;
        } else if (type instanceof TL_keyboard.TL_inlineButtonTypeUserProfile) {
            button = new TLRPC.TL_keyboardButtonUserProfile();
            button.user_id = ((TL_keyboard.TL_inlineButtonTypeUserProfile) type).user_id;
        } else if (type instanceof TL_keyboard.TL_inputInlineButtonTypeUserProfile) {
            button = new TLRPC.TL_inputKeyboardButtonUserProfile();
            button.inputUser = ((TL_keyboard.TL_inputInlineButtonTypeUserProfile) type).user_id;
        } else if (type instanceof TL_keyboard.TL_buttonTypeRequestPhone) {
            button = new TLRPC.TL_keyboardButtonRequestPhone();
        } else if (type instanceof TL_keyboard.TL_buttonTypeRequestGeoLocation) {
            button = new TLRPC.TL_keyboardButtonRequestGeoLocation();
        } else if (type instanceof TL_keyboard.TL_buttonTypeRequestPoll) {
            TL_keyboard.TL_buttonTypeRequestPoll value = (TL_keyboard.TL_buttonTypeRequestPoll) type;
            button = new TLRPC.TL_keyboardButtonRequestPoll();
            button.flags = value.flags;
            button.quiz = value.quiz;
        } else if (type instanceof TL_keyboard.TL_buttonTypeRequestPeer) {
            TL_keyboard.TL_buttonTypeRequestPeer value = (TL_keyboard.TL_buttonTypeRequestPeer) type;
            TLRPC.TL_keyboardButtonRequestPeer target = new TLRPC.TL_keyboardButtonRequestPeer();
            target.flags = value.flags;
            target.button_id = value.button_id;
            target.peer_type = value.peer_type;
            target.max_quantity = value.max_quantity;
            button = target;
        } else if (type instanceof TL_keyboard.TL_inputButtonTypeRequestPeer) {
            TL_keyboard.TL_inputButtonTypeRequestPeer value = (TL_keyboard.TL_inputButtonTypeRequestPeer) type;
            TLRPC.TL_keyboardButtonRequestPeer target = new TLRPC.TL_keyboardButtonRequestPeer();
            target.flags = value.flags;
            target.button_id = value.button_id;
            target.peer_type = value.peer_type;
            target.max_quantity = value.max_quantity;
            button = target;
        } else if (type instanceof TL_keyboard.TL_buttonTypeSimpleWebView) {
            button = new TLRPC.TL_keyboardButtonSimpleWebView();
            button.url = ((TL_keyboard.TL_buttonTypeSimpleWebView) type).url;
        } else {
            button = new TLRPC.TL_keyboardButton();
            button.disabled = !(type instanceof TL_keyboard.TL_buttonTypeDefault);
        }
        button.text = proto.getText();
        if (button.text == null) {
            TL_iv.RichText richText = proto instanceof TL_iv.textButton ? ((TL_iv.textButton) proto).text
                : proto instanceof TL_keyboard.PageButton ? ((TL_keyboard.PageButton) proto).text : null;
            StringBuilder text = new StringBuilder();
            appendText(richText, text);
            button.text = text.toString();
        }
        TL_keyboard.KeyboardButtonStyle keyboardStyle = proto instanceof TL_keyboard.KeyboardInlineButton
            ? ((TL_keyboard.KeyboardInlineButton) proto).style
            : proto instanceof TL_keyboard.KeyboardButton ? ((TL_keyboard.KeyboardButton) proto).style : null;
        TL_keyboard.RichButtonStyle richStyle = getStyle(proto);
        if (keyboardStyle != null || richStyle != null) {
            button.style = new TLRPC.TL_keyboardButtonStyle();
            if (keyboardStyle != null) {
                button.style.flags = keyboardStyle.flags;
                button.style.bg_primary = keyboardStyle.bg_primary;
                button.style.bg_danger = keyboardStyle.bg_danger;
                button.style.bg_success = keyboardStyle.bg_success;
                button.style.icon = keyboardStyle.icon;
            } else {
                button.style.bg_primary = richStyle.bg_primary;
                button.style.bg_danger = richStyle.bg_danger;
                button.style.bg_success = richStyle.bg_success;
            }
        }
        button.keyboardButtonProto = proto;
        setCached(proto, button);
        return button;
    }

    private static void appendText(TL_iv.RichText text, StringBuilder out) {
        if (text instanceof TL_iv.textPlain) out.append(((TL_iv.textPlain) text).text);
        else if (text instanceof TL_iv.textConcat) {
            for (TL_iv.RichText child : ((TL_iv.textConcat) text).texts) appendText(child, out);
        } else if (text != null) appendText(text.text, out);
    }

    private static TLRPC.KeyboardButton getCached(TL_keyboard.KeyboardButtonProto proto) {
        if (proto instanceof TL_iv.textButton) return ((TL_iv.textButton) proto).legacyButton;
        if (proto instanceof TL_keyboard.PageButton) return ((TL_keyboard.PageButton) proto).legacyButton;
        if (proto instanceof TL_keyboard.KeyboardInlineButton) return ((TL_keyboard.KeyboardInlineButton) proto).legacyButton;
        if (proto instanceof TL_keyboard.KeyboardButton) return ((TL_keyboard.KeyboardButton) proto).legacyButton;
        return null;
    }

    private static void setCached(TL_keyboard.KeyboardButtonProto proto, TLRPC.KeyboardButton button) {
        if (proto instanceof TL_iv.textButton) ((TL_iv.textButton) proto).legacyButton = button;
        else if (proto instanceof TL_keyboard.PageButton) ((TL_keyboard.PageButton) proto).legacyButton = button;
        else if (proto instanceof TL_keyboard.KeyboardInlineButton) ((TL_keyboard.KeyboardInlineButton) proto).legacyButton = button;
        else if (proto instanceof TL_keyboard.KeyboardButton) ((TL_keyboard.KeyboardButton) proto).legacyButton = button;
    }

    public static ArrayList<TLRPC.TL_keyboardButtonRow> toLegacyKeyboardRows(ArrayList<TL_keyboard.KeyboardButtonRow> wireRows) {
        ArrayList<TLRPC.TL_keyboardButtonRow> rows = new ArrayList<>(wireRows.size());
        for (TL_keyboard.KeyboardButtonRow wireRow : wireRows) {
            TLRPC.TL_keyboardButtonRow row = new TLRPC.TL_keyboardButtonRow();
            for (TL_keyboard.KeyboardButton button : wireRow.buttons) row.buttons.add(toLegacyButton(button));
            rows.add(row);
        }
        return rows;
    }

    public static ArrayList<TLRPC.TL_keyboardButtonRow> toLegacyInlineRows(ArrayList<TL_keyboard.KeyboardInlineButtonRow> wireRows) {
        ArrayList<TLRPC.TL_keyboardButtonRow> rows = new ArrayList<>(wireRows.size());
        for (TL_keyboard.KeyboardInlineButtonRow wireRow : wireRows) {
            TLRPC.TL_keyboardButtonRow row = new TLRPC.TL_keyboardButtonRow();
            for (TL_keyboard.KeyboardInlineButton button : wireRow.buttons) row.buttons.add(toLegacyButton(button));
            rows.add(row);
        }
        return rows;
    }

    public static ArrayList<TL_keyboard.KeyboardButtonRow> toKeyboardRows(ArrayList<TLRPC.TL_keyboardButtonRow> legacyRows) {
        ArrayList<TL_keyboard.KeyboardButtonRow> rows = new ArrayList<>(legacyRows.size());
        for (TLRPC.TL_keyboardButtonRow legacyRow : legacyRows) {
            TL_keyboard.KeyboardButtonRow row = new TL_keyboard.KeyboardButtonRow();
            for (TLRPC.KeyboardButton button : legacyRow.buttons) {
                if (button.keyboardButtonProto instanceof TL_keyboard.KeyboardButton) {
                    row.buttons.add((TL_keyboard.KeyboardButton) button.keyboardButtonProto);
                } else {
                    SerializedData output = new SerializedData();
                    button.serializeToStream(output);
                    SerializedData input = new SerializedData(output.toByteArray());
                    row.buttons.add(TL_keyboard.KeyboardButton.TLdeserialize(input, input.readInt32(true), true));
                    input.cleanup();
                    output.cleanup();
                }
            }
            rows.add(row);
        }
        return rows;
    }

    public static ArrayList<TL_keyboard.KeyboardInlineButtonRow> toInlineRows(ArrayList<TLRPC.TL_keyboardButtonRow> legacyRows) {
        ArrayList<TL_keyboard.KeyboardInlineButtonRow> rows = new ArrayList<>(legacyRows.size());
        for (TLRPC.TL_keyboardButtonRow legacyRow : legacyRows) {
            TL_keyboard.TL_keyboardInlineButtonRow row = new TL_keyboard.TL_keyboardInlineButtonRow();
            for (TLRPC.KeyboardButton button : legacyRow.buttons) {
                TL_keyboard.KeyboardInlineButton converted;
                if (button.keyboardButtonProto instanceof TL_keyboard.KeyboardInlineButton) {
                    converted = (TL_keyboard.KeyboardInlineButton) button.keyboardButtonProto;
                } else {
                    converted = new TL_keyboard.TL_keyboardInlineButton();
                    converted.text = button.text;
                    converted.type = toInlineType(button);
                    if (button.style != null) {
                        converted.style = new TL_keyboard.KeyboardButtonStyle();
                        converted.style.flags = button.style.flags;
                        converted.style.bg_primary = button.style.bg_primary;
                        converted.style.bg_danger = button.style.bg_danger;
                        converted.style.bg_success = button.style.bg_success;
                        converted.style.icon = button.style.icon;
                    }
                }
                row.buttons.add(converted);
            }
            rows.add(row);
        }
        return rows;
    }

    private static TL_keyboard.InlineButtonType toInlineType(TLRPC.KeyboardButton button) {
        if (button.keyboardButtonProto != null && button.keyboardButtonProto.getType() instanceof TL_keyboard.InlineButtonType) {
            return (TL_keyboard.InlineButtonType) button.keyboardButtonProto.getType();
        }
        if (button instanceof TLRPC.TL_keyboardButtonCallback) {
            TL_keyboard.TL_inlineButtonTypeCallback type = new TL_keyboard.TL_inlineButtonTypeCallback();
            type.flags = button.flags & 1;
            type.requires_password = button.requires_password;
            type.data = button.data;
            return type;
        }
        if (button instanceof TLRPC.TL_keyboardButtonUrl) {
            TL_keyboard.TL_inlineButtonTypeUrl type = new TL_keyboard.TL_inlineButtonTypeUrl();
            type.url = button.url;
            return type;
        }
        if (button instanceof TLRPC.TL_keyboardButtonUrlAuth) {
            TL_keyboard.TL_inlineButtonTypeUrlAuth type = new TL_keyboard.TL_inlineButtonTypeUrlAuth();
            type.flags = button.flags & 1;
            type.url = button.url;
            type.fwd_text = button.fwd_text;
            type.button_id = button.button_id;
            return type;
        }
        if (button instanceof TLRPC.TL_inputKeyboardButtonUrlAuth) {
            TL_keyboard.TL_inputInlineButtonTypeUrlAuth type = new TL_keyboard.TL_inputInlineButtonTypeUrlAuth();
            type.url = button.url;
            type.fwd_text = button.fwd_text;
            type.bot = button.bot;
            type.request_write_access = button.request_write_access;
            return type;
        }
        if (button instanceof TLRPC.TL_keyboardButtonSwitchInline) {
            TL_keyboard.TL_inlineButtonTypeSwitchInline type = new TL_keyboard.TL_inlineButtonTypeSwitchInline();
            type.same_peer = button.same_peer;
            type.query = button.query;
            type.peer_types = (button.flags & 2) != 0 ? button.peer_types : null;
            return type;
        }
        if (button instanceof TLRPC.TL_keyboardButtonGame) return new TL_keyboard.TL_inlineButtonTypeGame();
        if (button instanceof TLRPC.TL_keyboardButtonBuy) return new TL_keyboard.TL_inlineButtonTypeBuy();
        if (button instanceof TLRPC.TL_keyboardButtonWebView) {
            TL_keyboard.TL_inlineButtonTypeWebView type = new TL_keyboard.TL_inlineButtonTypeWebView();
            type.url = button.url;
            return type;
        }
        if (button instanceof TLRPC.TL_keyboardButtonCopy) {
            TL_keyboard.TL_inlineButtonTypeCopy type = new TL_keyboard.TL_inlineButtonTypeCopy();
            type.copy_text = ((TLRPC.TL_keyboardButtonCopy) button).copy_text;
            return type;
        }
        if (button instanceof TLRPC.TL_keyboardButtonUserProfile) {
            TL_keyboard.TL_inlineButtonTypeUserProfile type = new TL_keyboard.TL_inlineButtonTypeUserProfile();
            type.user_id = button.user_id;
            return type;
        }
        if (button instanceof TLRPC.TL_inputKeyboardButtonUserProfile) {
            TL_keyboard.TL_inputInlineButtonTypeUserProfile type = new TL_keyboard.TL_inputInlineButtonTypeUserProfile();
            type.user_id = button.inputUser;
            return type;
        }
        return new TL_keyboard.TL_inlineButtonTypeDisabled();
    }
}
