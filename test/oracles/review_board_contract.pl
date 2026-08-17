:- begin_tests(review_board_contract).

% REV-BOARD-001 shadow oracle. The visible submission identifiers and the
% identifiers carried by Read & rate actions must be the same set, exactly once,
% and the page must carry no pagination controls.
review_board_complete(Submissions, ReadActions, PaginationControls) :-
    PaginationControls = [],
    sort(Submissions, UniqueSubmissions),
    sort(ReadActions, UniqueActions),
    same_length(Submissions, UniqueSubmissions),
    same_length(ReadActions, UniqueActions),
    UniqueSubmissions = UniqueActions.

rateable_status('Pending').
rateable_status('Accept Queue').
rateable_status('Decline Queue').

board_visible_status(Status) :- Status \= 'Draft'.

test(all_rows_have_one_action) :-
    review_board_complete([s1, s2, s3], [s3, s1, s2], []).

test(missing_action_is_rejected, [fail]) :-
    review_board_complete([s1, s2, s3], [s1, s2], []).

test(duplicate_action_is_rejected, [fail]) :-
    review_board_complete([s1, s2], [s1, s1, s2], []).

test(pagination_is_rejected, [fail]) :-
    review_board_complete([s1, s2], [s1, s2], [next]).

test(only_active_review_queues_are_rateable, all(Status == ['Pending', 'Accept Queue', 'Decline Queue'])) :-
    rateable_status(Status).

test(draft_is_not_visible, [fail]) :-
    board_visible_status('Draft').

:- end_tests(review_board_contract).
