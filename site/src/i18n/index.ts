import en from './en.json';
import zhCn from './zh-cn.json';
import zhTw from './zh-tw.json';
import { localeOf, localize, localizeLinks, type Locale } from './locales';

export * from './locales';
export { format } from './format';

/** The shape every locale's strings must have. A missing key fails `astro check`. */
export type Strings = typeof en;

const all: Record<Locale, Strings> = { en, 'zh-cn': zhCn, 'zh-tw': zhTw };

/** The locale, strings and link helpers for the page at `url`. */
export function i18n(url: URL) {
  const locale = localeOf(url.pathname);
  return {
    locale,
    t: all[locale],
    /** A site route, such as `/docs/hosting`, in this page's language. */
    link: (route: string) => localize(route, locale),
    /** A string with markup, for `set:html`, with its site links in this page's language. */
    html: (markup: string) => localizeLinks(markup, locale),
  };
}
