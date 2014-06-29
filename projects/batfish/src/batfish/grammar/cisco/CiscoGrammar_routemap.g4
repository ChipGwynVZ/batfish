parser grammar CiscoGrammar_routemap;

import CiscoGrammarCommonParser;

options {
	tokenVocab = CiscoGrammarCommonLexer;
}

match_as_path_access_list_rm_stanza
:
	MATCH AS_PATH
	(
		name_list += DEC
	)+ NEWLINE
;

match_community_list_rm_stanza
:
	MATCH COMMUNITY
	(
		name_list +=
		(
			VARIABLE
			| DEC
		)
	)+ NEWLINE
;

match_ip_access_list_rm_stanza
:
	MATCH IP ADDRESS
	(
		name_list +=
		(
			VARIABLE
			| DEC
		)
	)+ NEWLINE
;

match_ip_prefix_list_rm_stanza
:
	MATCH IP ADDRESS PREFIX_LIST
	(
		name_list +=
		(
			VARIABLE
			| DEC
		)
	)+ NEWLINE
;

match_ipv6_rm_stanza
:
	MATCH IPV6 ~NEWLINE* NEWLINE
;

match_rm_stanza
:
	match_as_path_access_list_rm_stanza
	| match_community_list_rm_stanza
	| match_ip_access_list_rm_stanza
	| match_ip_prefix_list_rm_stanza
	| match_ipv6_rm_stanza
	| match_tag_rm_stanza
;

match_tag_rm_stanza
:
	MATCH TAG
	(
		tag_list += DEC
	)+ NEWLINE
;

null_rm_stanza
:
	NO?
	(
		DESCRIPTION
	) ~NEWLINE* NEWLINE
;

rm_stanza
:
	match_rm_stanza
	| null_rm_stanza
	| set_rm_stanza
;

route_map_stanza
:
	ROUTE_MAP firstname = VARIABLE route_map_tail
	(
		ROUTE_MAP name = VARIABLE
		{$firstname.text.equals($name.text)}?

		route_map_tail
	)*
;

route_map_tail
:
	rmt = access_list_action num = integer NEWLINE
	(
		rms_list += rm_stanza
	)* closing_comment
;

set_as_path_rm_stanza
:
	SET AS_PATH
	(
		as_list += integer
	)+ NEWLINE
;

set_as_path_prepend_rm_stanza
:
	SET AS_PATH PREPEND
	(
		as_list = integer
	)+ NEWLINE
;

set_community_additive_rm_stanza
:
	SET COMMUNITY
	(
		comm_list += community
	)+ ADDITIVE NEWLINE
;

set_comm_list_delete_rm_stanza
:
	SET COMM_LIST
	(
		name = DEC
		| name = VARIABLE
	) DELETE NEWLINE
;

set_community_rm_stanza
:
	SET COMMUNITY
	(
		comm_list += community
	)+ NEWLINE
;

set_ipv6_rm_stanza
:
	SET IPV6 ~NEWLINE* NEWLINE
;

set_local_preference_rm_stanza
:
	SET LOCAL_PREFERENCE pref = integer NEWLINE
;

set_metric_rm_stanza
:
	SET METRIC met = integer NEWLINE
;

set_next_hop_rm_stanza
:
	SET IP NEXT_HOP
	(
		nexthop_list += IP_ADDRESS
	)+ NEWLINE
;

set_origin_rm_stanza
:
	SET ORIGIN ~NEWLINE* NEWLINE
;

set_rm_stanza
:
	set_as_path_rm_stanza
	| set_as_path_prepend_rm_stanza
	| set_comm_list_delete_rm_stanza
	| set_community_rm_stanza
	| set_community_additive_rm_stanza
	| set_ipv6_rm_stanza
	| set_local_preference_rm_stanza
	| set_metric_rm_stanza
	| set_next_hop_rm_stanza
	| set_origin_rm_stanza
;

