package org.telegram.ui.iv;

import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_iv;
import org.telegram.tgnet.tl.TL_keyboard;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Pure, bounded conversion for an explicit own-name draft preview. */
public final class RichMessageSimpleConversion {
    public static final int STYLE_CHANGED = 1;
    public static final int TABLE_AS_TEXT = 2;
    public static final int BUTTONS_AS_LINKS = 4;
    public static final int INTERACTIVE_REMOVED = 8;
    public static final int MEDIA_NOT_ATTACHED = 16;
    public static final int UNKNOWN_CONTENT = 32;
    public static final int LIMIT_REACHED = 64;

    private static final int MAX_DEPTH = 48;
    private static final int MAX_NODES = 10000;
    private static final int MAX_CHARS = 65536;
    private static final int MAX_ENTITIES = 4096;

    public static final class Labels {
        public final String unknown, mediaNotAttached, file, audio, video, photo, web, animation, map, emoji, removedAction, channel;

        public Labels(String unknown, String mediaNotAttached, String file, String audio, String video,
                      String photo, String web, String animation, String map, String emoji, String removedAction, String channel) {
            this.unknown = label(unknown, "[Unsupported content]");
            this.mediaNotAttached = label(mediaNotAttached, "%1$s not attached");
            this.file = label(file, "File"); this.audio = label(audio, "Audio"); this.video = label(video, "Video");
            this.photo = label(photo, "Photo"); this.web = label(web, "Web content"); this.animation = label(animation, "Animation");
            this.map = label(map, "Map"); this.emoji = label(emoji, "[Emoji]");
            this.removedAction = label(removedAction, "[Interactive action removed]"); this.channel = label(channel, "Channel");
        }

        private static String label(String value, String fallback) {
            return value == null || value.isEmpty() ? fallback : value;
        }
    }

    public static final Labels DEFAULT_LABELS = new Labels(null, null, null, null, null, null, null, null, null, null, null, null);

    private RichMessageSimpleConversion() {}

    public static final class Result {
        public final String text;
        public final ArrayList<TLRPC.MessageEntity> entities;
        public final int warningFlags;
        public final boolean blocked;

        private Result(String text, ArrayList<TLRPC.MessageEntity> entities, int warnings, boolean blocked) {
            this.text = text;
            this.entities = entities;
            this.warningFlags = warnings;
            this.blocked = blocked;
        }

        public boolean isEmpty() {
            for (int i = 0; i < text.length(); i++) if (!Character.isWhitespace(text.charAt(i))) return false;
            return true;
        }
    }

    public static Result convert(TL_iv.RichMessage rich) {
        return convert(rich, DEFAULT_LABELS);
    }

    public static Result convert(TL_iv.RichMessage rich, Labels labels) {
        if (rich == null) return new Result("", new ArrayList<>(), 0, false);
        Writer writer = new Writer(labels == null ? DEFAULT_LABELS : labels);
        try {
            if (rich.part) return new Result("", new ArrayList<>(), UNKNOWN_CONTENT, true);
            writer.indexDocuments(rich.documents);
            writer.blocks(rich.blocks, 0, 0);
            writer.entities.sort(Comparator.comparingInt((TLRPC.MessageEntity e) -> e.offset)
                .thenComparing((a, b) -> Integer.compare(b.length, a.length)));
            return new Result(writer.out.toString(), writer.entities, writer.warnings,
                !writer.knownContent && (writer.warnings & UNKNOWN_CONTENT) != 0);
        } catch (LimitReached ignored) {
            // Never expose a truncated sendable draft after any traversal/size failure.
            return new Result("", new ArrayList<>(), writer.warnings | LIMIT_REACHED, true);
        } catch (MalformedContent ignored) {
            return new Result("", new ArrayList<>(), writer.warnings | UNKNOWN_CONTENT, true);
        }
    }

    private static final class LimitReached extends RuntimeException {}
    private static final class MalformedContent extends RuntimeException {}

    private static final class Writer {
        final StringBuilder out = new StringBuilder();
        final ArrayList<TLRPC.MessageEntity> entities = new ArrayList<>();
        final HashMap<Long, String> documentNames = new HashMap<>();
        final Labels labels;
        int warnings, nodes, metadataChars;
        boolean knownContent, tableCell, hasCode;

        Writer(Labels labels) { this.labels = labels; }

        void visit(int depth) {
            if (depth > MAX_DEPTH || ++nodes > MAX_NODES) throw new LimitReached();
        }

        void append(String value) {
            if (value == null || value.isEmpty()) return;
            if (value.length() > MAX_CHARS - out.length() - metadataChars) throw new LimitReached();
            if (!tableCell || (value.indexOf('\n') < 0 && value.indexOf('\r') < 0)) {
                out.append(value);
                return;
            }
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c == '\r' || c == '\n') {
                    if (c == '\r' && i + 1 < value.length() && value.charAt(i + 1) == '\n') i++;
                    if (out.length() + 3 + metadataChars > MAX_CHARS) throw new LimitReached();
                    out.append(" ↵ ");
                } else {
                    if (out.length() + metadataChars == MAX_CHARS) throw new LimitReached();
                    out.append(c);
                }
            }
        }

        void value(String value) {
            append(value);
            if (value != null) for (int i = 0; i < value.length(); i++) {
                if (!Character.isWhitespace(value.charAt(i))) { knownContent = true; break; }
            }
        }

        void line(int indent) {
            if (out.length() > 0) append("\n");
            for (int i = 0; i < indent; i++) append("  ");
        }

        void unknown() {
            warnings |= UNKNOWN_CONTENT;
            append(labels.unknown);
        }

        void indexDocuments(List<TLRPC.Document> documents) {
            if (documents == null) return;
            for (TLRPC.Document document : documents) {
                visit(0);
                if (document == null || document.attributes == null) continue;
                String name = null;
                for (TLRPC.DocumentAttribute attribute : document.attributes) {
                    visit(0);
                    if (attribute instanceof TLRPC.TL_documentAttributeFilename && attribute.file_name != null) {
                        name = attribute.file_name;
                        break;
                    }
                    if (attribute instanceof TLRPC.TL_documentAttributeAudio && attribute.title != null && !attribute.title.isEmpty()) {
                        name = attribute.title;
                    }
                }
                if (name != null) documentNames.put(document.id, name);
            }
        }

        void blocks(List<TL_iv.PageBlock> blocks, int indent, int depth) {
            if (blocks == null) { unknown(); return; }
            for (int i = 0; i < blocks.size(); i++) {
                if (i > 0) line(indent);
                block(blocks.get(i), indent, depth + 1);
            }
        }

        void block(TL_iv.PageBlock block, int indent, int depth) {
            visit(depth);
            if (block == null) { unknown(); return; }
            if (block instanceof TL_iv.pageBlockTitle || block instanceof TL_iv.pageBlockSubtitle
                || block instanceof TL_iv.pageBlockHeader || block instanceof TL_iv.pageBlockSubheader
                || block instanceof TL_iv.pageBlockHeading1 || block instanceof TL_iv.pageBlockHeading2
                || block instanceof TL_iv.pageBlockHeading3 || block instanceof TL_iv.pageBlockHeading4
                || block instanceof TL_iv.pageBlockHeading5 || block instanceof TL_iv.pageBlockHeading6
                || block instanceof TL_iv.pageBlockKicker) {
                warnings |= STYLE_CHANGED;
                int start = out.length(); text(block.text, depth + 1, false);
                entity(new TLRPC.TL_messageEntityBold(), start);
            } else if (block instanceof TL_iv.pageBlockParagraph || block instanceof TL_iv.pageBlockFooter) {
                text(block.text, depth + 1, false);
            } else if (block instanceof TL_iv.pageBlockPreformatted) {
                int start = out.length(); text(block.text, depth + 1, true);
                TLRPC.TL_messageEntityPre pre = new TLRPC.TL_messageEntityPre();
                pre.language = ((TL_iv.pageBlockPreformatted) block).language;
                if (pre.language == null) pre.language = "";
                entity(pre, start);
            } else if (block instanceof TL_iv.pageBlockMath) {
                warnings |= STYLE_CHANGED;
                int start = out.length(); String source = ((TL_iv.pageBlockMath) block).source;
                if (source == null) unknown(); else value(source);
                entity(new TLRPC.TL_messageEntityCode(), start);
            } else if (block instanceof TL_iv.pageBlockList) {
                list((TL_iv.pageBlockList) block, indent, depth + 1);
            } else if (block instanceof TL_iv.pageBlockOrderedList) {
                orderedList((TL_iv.pageBlockOrderedList) block, indent, depth + 1);
            } else if (block instanceof TL_iv.pageBlockBlockquote) {
                TL_iv.pageBlockBlockquote quote = (TL_iv.pageBlockBlockquote) block;
                append("> "); text(quote.text, depth + 1, false); author(quote.caption, indent, depth + 1);
                warnings |= STYLE_CHANGED;
            } else if (block instanceof TL_iv.pageBlockPullquote) {
                TL_iv.pageBlockPullquote quote = (TL_iv.pageBlockPullquote) block;
                append("> "); text(quote.text, depth + 1, false); author(quote.caption, indent, depth + 1);
                warnings |= STYLE_CHANGED;
            } else if (block instanceof TL_iv.pageBlockBlockquoteBlocks) {
                TL_iv.pageBlockBlockquoteBlocks quote = (TL_iv.pageBlockBlockquoteBlocks) block;
                append("> "); blocks(quote.blocks, indent + 1, depth + 1); author(quote.caption, indent, depth + 1);
                warnings |= STYLE_CHANGED;
            } else if (block instanceof TL_iv.pageBlockDetails) {
                TL_iv.pageBlockDetails details = (TL_iv.pageBlockDetails) block;
                int start = out.length(); text(details.title, depth + 1, false); entity(new TLRPC.TL_messageEntityBold(), start);
                if (details.blocks != null && !details.blocks.isEmpty()) { line(indent + 1); blocks(details.blocks, indent + 1, depth + 1); }
                else if (details.blocks == null) { line(indent + 1); unknown(); }
                warnings |= STYLE_CHANGED;
            } else if (block instanceof TL_iv.pageBlockTable) {
                table((TL_iv.pageBlockTable) block, indent, depth + 1);
            } else if (block instanceof TL_iv.pageBlockButtonRow) {
                warnings |= STYLE_CHANGED;
                List<TL_keyboard.PageButton> buttons = ((TL_iv.pageBlockButtonRow) block).buttons;
                if (buttons == null) { unknown(); return; }
                for (int i = 0; i < buttons.size(); i++) {
                    visit(depth + 1); if (i > 0) append(" · ");
                    TL_keyboard.PageButton button = buttons.get(i);
                    if (button == null) unknown(); else button(button.text, button.type, depth + 1, false);
                }
            } else if (block instanceof TL_iv.pageBlockDocument) {
                media(labels.file, ((TL_iv.pageBlockDocument) block).document_id); caption(block.caption, indent, depth + 1);
            } else if (block instanceof TL_iv.pageBlockAudio) {
                media(labels.audio, ((TL_iv.pageBlockAudio) block).audio_id); caption(block.caption, indent, depth + 1);
            } else if (block instanceof TL_iv.pageBlockVideo) {
                TL_iv.pageBlockVideo video = (TL_iv.pageBlockVideo) block;
                media(video.loop && video.autoplay ? labels.animation : labels.video, video.video_id); caption(block.caption, indent, depth + 1);
            } else if (block instanceof TL_iv.pageBlockPhoto) {
                TL_iv.pageBlockPhoto photo = (TL_iv.pageBlockPhoto) block;
                media(labels.photo, 0); caption(block.caption, indent, depth + 1);
                if (photo.url != null && !photo.url.isEmpty()) { line(indent); link(photo.url, photo.url, false); }
            } else if (block instanceof TL_iv.pageBlockCollage) {
                TL_iv.pageBlockCollage group = (TL_iv.pageBlockCollage) block;
                blocks(group.items, indent, depth + 1); caption(group.caption, indent, depth + 1);
            } else if (block instanceof TL_iv.pageBlockSlideshow) {
                TL_iv.pageBlockSlideshow group = (TL_iv.pageBlockSlideshow) block;
                blocks(group.items, indent, depth + 1); caption(group.caption, indent, depth + 1);
            } else if (block instanceof TL_iv.pageBlockCover) {
                block(((TL_iv.pageBlockCover) block).cover, indent, depth + 1);
            } else if (block instanceof TL_iv.pageBlockEmbed) {
                TL_iv.pageBlockEmbed embed = (TL_iv.pageBlockEmbed) block;
                boolean knownBefore = knownContent;
                media(labels.web, 0);
                knownContent = knownBefore;
                warnings |= INTERACTIVE_REMOVED | MEDIA_NOT_ATTACHED;
                if (embed.url != null && !embed.url.isEmpty()) { line(indent); link(embed.url, embed.url, false); }
                if (embed.html != null && !embed.html.isEmpty()) {
                    // Native embedded HTML cannot be faithfully rendered as ordinary message text.
                    // Keep its explicit placeholder, URL and caption rather than exposing implementation payload.
                    warnings |= UNKNOWN_CONTENT;
                }
                caption(embed.caption, indent, depth + 1);
            } else if (block instanceof TL_iv.pageBlockEmbedPost) {
                TL_iv.pageBlockEmbedPost post = (TL_iv.pageBlockEmbedPost) block;
                warnings |= STYLE_CHANGED | MEDIA_NOT_ATTACHED;
                value(post.author); if (post.date != 0) { append(" · "); value(date(post.date)); }
                if (post.url != null && !post.url.isEmpty()) { line(indent); link(post.url, post.url, false); }
                if (post.blocks != null && !post.blocks.isEmpty()) { line(indent); blocks(post.blocks, indent, depth + 1); }
                else if (post.blocks == null) { line(indent); unknown(); }
                caption(post.caption, indent, depth + 1);
            } else if (block instanceof TL_iv.pageBlockRelatedArticles) {
                TL_iv.pageBlockRelatedArticles articles = (TL_iv.pageBlockRelatedArticles) block;
                text(articles.title, depth + 1, false);
                if (articles.articles == null) { unknown(); return; }
                for (TL_iv.pageRelatedArticle article : articles.articles) {
                    visit(depth + 1); line(indent);
                    if (article == null) { unknown(); continue; }
                    link(article.title == null ? article.url : article.title, article.url, false);
                    if (article.description != null) { append(" — "); value(article.description); }
                    if (article.author != null) { append(" — "); value(article.author); }
                    if (article.published_date != 0) { append(" · "); value(date(article.published_date)); }
                    if (article.photo_id != 0) warnings |= MEDIA_NOT_ATTACHED;
                }
            } else if (block instanceof TL_iv.pageBlockAuthorDate) {
                TL_iv.pageBlockAuthorDate author = (TL_iv.pageBlockAuthorDate) block;
                text(author.author, depth + 1, false);
                if (author.published_date != 0) { append(" · "); value(date(author.published_date)); }
            } else if (block instanceof TL_iv.pageBlockChannel) {
                TLRPC.Chat chat = ((TL_iv.pageBlockChannel) block).channel;
                if (chat == null) unknown(); else {
                    String title = chat.title == null ? labels.channel : chat.title;
                    if (chat.username != null && chat.username.matches("[A-Za-z][A-Za-z0-9_]{3,31}")) link(title, "https://t.me/" + chat.username, false);
                    else value(title);
                }
                warnings |= STYLE_CHANGED;
            } else if (block instanceof TL_iv.pageBlockMap) {
                TLRPC.GeoPoint geo = ((TL_iv.pageBlockMap) block).geo;
                media(labels.map, 0);
                if (geo != null && !(geo instanceof TLRPC.TL_geoPointEmpty)) { append(" "); value(geo.lat + ", " + geo._long); }
                caption(block.caption, indent, depth + 1);
            } else if (block instanceof TL_iv.inputPageBlockMap) {
                TLRPC.InputGeoPoint geo = ((TL_iv.inputPageBlockMap) block).geo;
                media(labels.map, 0);
                if (geo != null && !(geo instanceof TLRPC.TL_inputGeoPointEmpty)) { append(" "); value(geo.lat + ", " + geo._long); }
                caption(block.caption, indent, depth + 1);
            } else if (block instanceof TL_iv.pageBlockThinking) {
                warnings |= STYLE_CHANGED; text(block.text, depth + 1, false);
            } else if (block instanceof TL_iv.pageBlockDivider) {
                append("———"); knownContent = true; warnings |= STYLE_CHANGED;
            } else if (block instanceof TL_iv.pageBlockAnchor) {
                warnings |= STYLE_CHANGED;
            } else {
                unknown();
                if (block.text != null) { append(" "); text(block.text, depth + 1, false); }
                caption(block.caption, indent, depth + 1);
            }
        }

        void list(TL_iv.pageBlockList list, int indent, int depth) {
            if (list.items == null) { unknown(); return; }
            for (int i = 0; i < list.items.size(); i++) {
                visit(depth); if (i > 0) line(indent);
                TL_iv.PageListItem item = list.items.get(i);
                if (item == null) { unknown(); continue; }
                append(item.checkbox ? (item.checked ? "[x] " : "[ ] ") : list.ordered ? (i + 1) + ". " : "• ");
                if (item instanceof TL_iv.TL_pageListItemText) text(((TL_iv.TL_pageListItemText) item).text, depth + 1, false);
                else if (item instanceof TL_iv.TL_pageListItemBlocks) blocks(((TL_iv.TL_pageListItemBlocks) item).blocks, indent + 1, depth + 1);
                else unknown();
            }
        }

        void orderedList(TL_iv.pageBlockOrderedList list, int indent, int depth) {
            if (list.items == null) { unknown(); return; }
            long number = (list.flags & 1) != 0 || list.start != 0 ? list.start : list.reversed ? list.items.size() : 1;
            for (int i = 0; i < list.items.size(); i++) {
                visit(depth); if (i > 0) line(indent);
                TL_iv.PageListOrderedItem item = list.items.get(i);
                if (item == null) { unknown(); continue; }
                if ((item.flags & 8) != 0 || item.value != 0) number = item.value;
                if (item.checkbox) append(item.checked ? "[x] " : "[ ] ");
                else if (item.num != null && !item.num.isEmpty()) { value(item.num); append(". "); }
                else { append(Long.toString(number)); append(". "); }
                if (item.type != null || list.type != null) warnings |= STYLE_CHANGED;
                if (item instanceof TL_iv.TL_pageListOrderedItemText) text(((TL_iv.TL_pageListOrderedItemText) item).text, depth + 1, false);
                else if (item instanceof TL_iv.TL_pageListOrderedItemBlocks) blocks(((TL_iv.TL_pageListOrderedItemBlocks) item).blocks, indent + 1, depth + 1);
                else unknown();
                number += list.reversed ? -1 : 1;
            }
        }

        void table(TL_iv.pageBlockTable table, int indent, int depth) {
            warnings |= TABLE_AS_TEXT;
            int titleStart = out.length(); text(table.title, depth + 1, false);
            entity(new TLRPC.TL_messageEntityBold(), titleStart);
            if (table.rows == null) { unknown(); return; }
            for (int r = 0; r < table.rows.size(); r++) {
                visit(depth); if (r > 0 || out.length() > titleStart) line(indent);
                TL_iv.pageTableRow row = table.rows.get(r);
                if (row == null || row.cells == null) { unknown(); continue; }
                for (int c = 0; c < row.cells.size(); c++) {
                    visit(depth + 1); if (c > 0) append(" | ");
                    TL_iv.pageTableCell cell = row.cells.get(c);
                    if (cell == null) { unknown(); continue; }
                    if (cell.colspan < 0 || cell.rowspan < 0) throw new MalformedContent();
                    int start = out.length(); boolean oldCell = tableCell; tableCell = true;
                    if (cell.text != null) text(cell.text, depth + 1, false);
                    tableCell = oldCell;
                    if (cell.header) entity(new TLRPC.TL_messageEntityBold(), start);
                }
            }
        }

        void author(TL_iv.RichText author, int indent, int depth) {
            if (author == null || author instanceof TL_iv.textEmpty) return;
            line(indent); append("— "); text(author, depth, false);
        }

        void caption(TL_iv.PageCaption caption, int indent, int depth) {
            if (caption == null) return;
            visit(depth);
            if (caption.text != null && !(caption.text instanceof TL_iv.textEmpty)) { line(indent); text(caption.text, depth + 1, false); }
            author(caption.credit, indent, depth + 1);
        }

        void media(String kind, long documentId) {
            warnings |= MEDIA_NOT_ATTACHED;
            append("["); append(labels.mediaNotAttached.replace("%1$s", kind).replace("%s", kind));
            String name = documentNames.get(documentId);
            if (name != null && !name.isEmpty()) { append(": "); value(name); }
            append("]"); knownContent = true;
        }

        void text(TL_iv.RichText rich, int depth, boolean code) {
            visit(depth);
            if (rich == null) { unknown(); return; }
            if (rich instanceof TL_iv.textEmpty) return;
            if (rich instanceof TL_iv.textPlain) {
                String plain = ((TL_iv.textPlain) rich).text;
                if (plain == null) unknown(); else value(plain);
                return;
            }
            if (rich instanceof TL_iv.textConcat) {
                if (rich.texts == null) { unknown(); return; }
                for (TL_iv.RichText child : rich.texts) text(child, depth + 1, code);
                return;
            }
            if (rich instanceof TL_iv.textButton) {
                TL_iv.textButton button = (TL_iv.textButton) rich;
                if (button.style != null) warnings |= STYLE_CHANGED;
                button(button.text, button.type, depth + 1, code); return;
            }
            if (rich instanceof TL_iv.textCustomEmoji) {
                String alt = ((TL_iv.textCustomEmoji) rich).alt;
                if (alt == null || alt.isEmpty()) append(labels.emoji); else value(alt);
                knownContent = true; warnings |= STYLE_CHANGED; return;
            }
            if (rich instanceof TL_iv.textImage) { media(labels.photo, ((TL_iv.textImage) rich).document_id); return; }
            if (rich instanceof TL_iv.textMath) {
                warnings |= STYLE_CHANGED; int start = out.length(); String source = ((TL_iv.textMath) rich).source;
                if (source == null) unknown(); else value(source);
                if (!code) entity(new TLRPC.TL_messageEntityCode(), start); return;
            }
            if (rich instanceof TL_iv.textDiff) {
                warnings |= STYLE_CHANGED; TL_iv.textDiff diff = (TL_iv.textDiff) rich;
                append("[- "); text(diff.old_text, depth + 1, code); append(" -] [+ "); text(diff.text, depth + 1, code); append(" +]"); return;
            }
            if (rich instanceof TL_iv.textDate) {
                TL_iv.textDate date = (TL_iv.textDate) rich; int start = out.length();
                if (date.text != null) text(date.text, depth + 1, code);
                boolean labeled = out.length() > start;
                if (labeled && !code && !nonFormattingOverlap(start)) {
                    TLRPC.TL_messageEntityFormattedDate entity = new TLRPC.TL_messageEntityFormattedDate();
                    entity.flags = date.flags; entity.date = date.date; entity.applyFlags();
                    entity(entity, start);
                } else {
                    if (labeled) append(" ("); value(date(date.date)); if (labeled) append(")");
                    warnings |= STYLE_CHANGED;
                }
                return;
            }
            int start = out.length(); TLRPC.MessageEntity entity = null;
            if (rich instanceof TL_iv.textBold) entity = new TLRPC.TL_messageEntityBold();
            else if (rich instanceof TL_iv.textItalic) entity = new TLRPC.TL_messageEntityItalic();
            else if (rich instanceof TL_iv.textUnderline) entity = new TLRPC.TL_messageEntityUnderline();
            else if (rich instanceof TL_iv.textStrike) entity = new TLRPC.TL_messageEntityStrike();
            else if (rich instanceof TL_iv.textSpoiler) entity = new TLRPC.TL_messageEntitySpoiler();
            else if (rich instanceof TL_iv.textFixed) {
                text(rich.text, depth + 1, true); if (!code) entity(new TLRPC.TL_messageEntityCode(), start); return;
            } else if (rich instanceof TL_iv.textUrl) {
                text(rich.text, depth + 1, code); textLink(start, rich.url, code); return;
            } else if (rich instanceof TL_iv.textEmail) {
                text(rich.text, depth + 1, code); textLink(start, rich.email == null ? null : "mailto:" + rich.email, code); return;
            } else if (rich instanceof TL_iv.textPhone) {
                text(rich.text, depth + 1, code); String phone = ((TL_iv.textPhone) rich).phone;
                textLink(start, phone == null ? null : "tel:" + phone, code); return;
            } else if (rich instanceof TL_iv.textMentionName) {
                text(rich.text, depth + 1, code); long id = ((TL_iv.textMentionName) rich).user_id;
                if (id > 0) textLink(start, "tg://user?id=" + id, code);
                else warnings |= STYLE_CHANGED;
                return;
            } else if (rich instanceof TL_iv.textMention) entity = new TLRPC.TL_messageEntityMention();
            else if (rich instanceof TL_iv.textHashtag) entity = new TLRPC.TL_messageEntityHashtag();
            else if (rich instanceof TL_iv.textBotCommand) entity = new TLRPC.TL_messageEntityBotCommand();
            else if (rich instanceof TL_iv.textCashtag) entity = new TLRPC.TL_messageEntityCashtag();
            else if (rich instanceof TL_iv.textAutoUrl) entity = new TLRPC.TL_messageEntityUrl();
            else if (rich instanceof TL_iv.textAutoEmail) entity = new TLRPC.TL_messageEntityEmail();
            else if (rich instanceof TL_iv.textAutoPhone) entity = new TLRPC.TL_messageEntityPhone();
            else if (rich instanceof TL_iv.textBankCard) entity = new TLRPC.TL_messageEntityBankCard();
            else if (rich instanceof TL_iv.textSubscript || rich instanceof TL_iv.textSuperscript
                || rich instanceof TL_iv.textMarked || rich instanceof TL_iv.textTonAddress || rich instanceof TL_iv.textAnchor) warnings |= STYLE_CHANGED;
            else {
                unknown(); if (rich.text != null) text(rich.text, depth + 1, code);
                if (rich.texts != null) for (TL_iv.RichText child : rich.texts) text(child, depth + 1, code);
                return;
            }
            text(rich.text, depth + 1, code);
            if (entity != null && !code) entity(entity, start);
            else if (entity != null) warnings |= STYLE_CHANGED;
        }

        void button(TL_iv.RichText label, TL_keyboard.InlineButtonType type, int depth, boolean code) {
            int start = out.length(); text(label, depth + 1, code);
            String url = null;
            if (type instanceof TL_keyboard.TL_inlineButtonTypeUrl) url = ((TL_keyboard.TL_inlineButtonTypeUrl) type).url;
            else if (type instanceof TL_keyboard.TL_inlineButtonTypeUserProfile) {
                long id = ((TL_keyboard.TL_inlineButtonTypeUserProfile) type).user_id;
                if (id > 0) url = "tg://user?id=" + id;
            } else if (type instanceof TL_keyboard.TL_inputInlineButtonTypeUserProfile) {
                TLRPC.InputUser user = ((TL_keyboard.TL_inputInlineButtonTypeUserProfile) type).user_id;
                if (user instanceof TLRPC.TL_inputUser && user.user_id > 0) url = "tg://user?id=" + user.user_id;
            }
            if (url != null && !url.isEmpty()) {
                if (out.length() == start) value(url);
                boolean linked = !code && safeUrl(url);
                textLink(start, url, code); warnings |= linked ? BUTTONS_AS_LINKS : INTERACTIVE_REMOVED;
            } else {
                append(" "); append(labels.removedAction); warnings |= INTERACTIVE_REMOVED;
            }
        }

        void link(String label, String url, boolean code) {
            int start = out.length(); value(label); textLink(start, url, code);
        }

        void textLink(int start, String url, boolean code) {
            if (url == null || url.isEmpty()) { warnings |= STYLE_CHANGED; return; }
            if (out.length() == start) value(url);
            if (!code && safeUrl(url)) {
                for (TLRPC.MessageEntity existing : entities) {
                    if (existing.offset >= out.length() || existing.offset + existing.length <= start || formatting(existing)) continue;
                    if (existing instanceof TLRPC.TL_messageEntityTextUrl && existing.offset == start
                        && existing.length == out.length() - start && url.equals(existing.url)) return;
                    // A normal message cannot put two executable destinations on the same label.
                    // Keep the inner destination and make this target visible as ordinary text.
                    append(" ("); value(url); append(")"); warnings |= STYLE_CHANGED; return;
                }
                TLRPC.TL_messageEntityTextUrl link = new TLRPC.TL_messageEntityTextUrl(); link.url = url; entity(link, start);
            } else {
                append(" ("); value(url); append(")"); warnings |= STYLE_CHANGED;
            }
        }

        boolean safeUrl(String url) {
            if (url.length() > MAX_CHARS) throw new LimitReached();
            for (int i = 0; i < url.length(); i++) if (Character.isISOControl(url.charAt(i))) return false;
            return url.regionMatches(true, 0, "https://", 0, 8) || url.regionMatches(true, 0, "http://", 0, 7)
                || url.regionMatches(true, 0, "tg://", 0, 5) || url.regionMatches(true, 0, "mailto:", 0, 7)
                || url.regionMatches(true, 0, "tel:", 0, 4);
        }

        void entity(TLRPC.MessageEntity entity, int start) {
            int length = out.length() - start;
            if (length <= 0) return;
            if (entities.size() >= MAX_ENTITIES) throw new LimitReached();
            entity.offset = start; entity.length = length;
            for (int i = entities.size() - 1; i >= 0; i--) {
                TLRPC.MessageEntity existing = entities.get(i);
                if (existing.offset < start || existing.offset > start + length) continue;
                if (existing.getClass() == entity.getClass() && existing.offset == start && existing.length == length) return;
            }
            boolean isCode = entity instanceof TLRPC.TL_messageEntityCode || entity instanceof TLRPC.TL_messageEntityPre;
            if (!isCode && !formatting(entity)) for (TLRPC.MessageEntity existing : entities) {
                if (existing.offset < start + length && existing.offset + existing.length > start && !formatting(existing)) {
                    warnings |= STYLE_CHANGED; return;
                }
            }
            if (isCode || hasCode) for (int i = entities.size() - 1; i >= 0; i--) {
                TLRPC.MessageEntity existing = entities.get(i);
                if (existing.offset < start + length && existing.offset + existing.length > start) {
                    if (isCode) {
                        String removedMetadata = metadata(existing);
                        if (removedMetadata != null) metadataChars -= removedMetadata.length();
                        entities.remove(i); warnings |= STYLE_CHANGED;
                    }
                    else if (existing instanceof TLRPC.TL_messageEntityCode || existing instanceof TLRPC.TL_messageEntityPre) {
                        warnings |= STYLE_CHANGED; return;
                    }
                }
            }
            if (isCode) hasCode = true;
            String metadata = metadata(entity);
            if (metadata != null) {
                if (metadata.length() > MAX_CHARS - out.length() - metadataChars) throw new LimitReached();
                metadataChars += metadata.length();
            }
            entities.add(entity);
        }

        boolean formatting(TLRPC.MessageEntity entity) {
            return entity instanceof TLRPC.TL_messageEntityBold || entity instanceof TLRPC.TL_messageEntityItalic
                || entity instanceof TLRPC.TL_messageEntityUnderline || entity instanceof TLRPC.TL_messageEntityStrike
                || entity instanceof TLRPC.TL_messageEntitySpoiler;
        }

        boolean nonFormattingOverlap(int start) {
            for (TLRPC.MessageEntity entity : entities) {
                if (!formatting(entity) && entity.offset < out.length() && entity.offset + entity.length > start) return true;
            }
            return false;
        }

        String metadata(TLRPC.MessageEntity entity) {
            return entity instanceof TLRPC.TL_messageEntityTextUrl ? entity.url
                : entity instanceof TLRPC.TL_messageEntityPre ? entity.language : null;
        }

        String date(int seconds) {
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm 'UTC'", Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            return format.format(new Date(seconds * 1000L));
        }
    }
}
